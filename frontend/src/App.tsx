import { useCallback, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import ReactPlayer from 'react-player'
import { PlayerPanel } from './components/PlayerPanel'
import { ResultsPanel } from './components/ResultsPanel'
import { SearchHero } from './components/SearchHero'
import { TopBar } from './components/TopBar'
import { UploadPanel } from './components/UploadPanel'
import type { SearchResult } from './types/search'
import type {
  UploadConfirmResponse,
  UploadFeedback,
  UploadInitRequestBody,
  UploadInitResponse,
} from './types/upload'
import { resolveVideoUrl } from './utils/searchUtils'
import { getApiErrorMessage, resolveUploadMimeType } from './utils/uploadUtils'
import './App.css'

const SEARCH_ENDPOINT = '/api/v1/search'
const UPLOAD_INIT_ENDPOINT = '/api/v1/upload/init'
const UPLOAD_CONFIRM_ENDPOINT = '/api/v1/upload/confirm'

function App() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[]>([])
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [uploadFeedback, setUploadFeedback] = useState<UploadFeedback | null>(null)
  const [playbackSeconds, setPlaybackSeconds] = useState(0)
  const [pendingSeekSeconds, setPendingSeekSeconds] = useState<number | null>(null)
  const playerRef = useRef<ReactPlayer | null>(null)

  const selectedResult = useMemo(() => {
    if (selectedIndex == null) {
      return null
    }

    return results[selectedIndex] ?? null
  }, [results, selectedIndex])

  const selectedVideoUrl = useMemo(() => {
    if (!selectedResult) {
      return null
    }

    return resolveVideoUrl(selectedResult, results)
  }, [selectedResult, results])

  const executeSearch = useCallback(async (term: string) => {
    try {
      setIsLoading(true)
      setError(null)

      const response = await fetch(`${SEARCH_ENDPOINT}?q=${encodeURIComponent(term)}`)
      if (!response.ok) {
        throw new Error(`Search failed with status ${response.status}.`)
      }

      const payload = (await response.json()) as SearchResult[]
      setResults(payload)

      if (payload.length === 0) {
        setSelectedIndex(null)
        setPendingSeekSeconds(null)
        setPlaybackSeconds(0)
        return
      }

      const firstPlayable = payload.findIndex((item) => resolveVideoUrl(item, payload) !== null)
      const initialIndex = firstPlayable >= 0 ? firstPlayable : 0
      setSelectedIndex(initialIndex)
      setPendingSeekSeconds(Number(payload[initialIndex].timestamp) || 0)
      setPlaybackSeconds(0)
    } catch (searchError) {
      const message = searchError instanceof Error ? searchError.message : 'Unknown search error.'
      setError(message)
      setResults([])
      setSelectedIndex(null)
      setPendingSeekSeconds(null)
      setPlaybackSeconds(0)
    } finally {
      setIsLoading(false)
    }
  }, [])

  const handleSearchSubmit = useCallback(
    (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault()

      const trimmed = query.trim()
      if (!trimmed) {
        setError('Type a concept first, for example: "playing basketball".')
        return
      }

      void executeSearch(trimmed)
    },
    [executeSearch, query],
  )

  const handleQueryChange = useCallback((value: string) => {
    setQuery(value)
  }, [])

  const handleUploadFileSelected = useCallback((file: File | null) => {
    setUploadFile(file)

    if (file) {
      setUploadFeedback(null)
    }
  }, [])

  const handleUploadStart = useCallback(async () => {
    if (!uploadFile) {
      setUploadFeedback({
        kind: 'error',
        message: 'Select an MP4 or MOV file before starting upload.',
      })
      return
    }

    const mimeType = resolveUploadMimeType(uploadFile)
    if (!mimeType) {
      setUploadFeedback({
        kind: 'error',
        message: 'Only MP4 or MOV uploads are supported by the backend.',
      })
      return
    }

    try {
      setIsUploading(true)
      setUploadFeedback({
        kind: 'uploading',
        message: 'Requesting secure upload URL...',
      })

      const initRequestBody: UploadInitRequestBody = {
        filename: uploadFile.name,
        mimeType,
        sizeInBytes: uploadFile.size,
      }

      const initResponse = await fetch(UPLOAD_INIT_ENDPOINT, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(initRequestBody),
      })

      if (!initResponse.ok) {
        throw new Error(await getApiErrorMessage(initResponse, 'Upload initialization failed'))
      }

      const initPayload = (await initResponse.json()) as UploadInitResponse
      setUploadFeedback({
        kind: 'uploading',
        message: 'Uploading video bytes to object storage...',
        mediaAssetId: initPayload.mediaAssetId,
      })

      const uploadToStorageResponse = await fetch(initPayload.uploadUrl, {
        method: 'PUT',
        headers: {
          'Content-Type': mimeType,
        },
        body: uploadFile,
      })

      if (!uploadToStorageResponse.ok) {
        throw new Error(`Upload to storage failed with status ${uploadToStorageResponse.status}.`)
      }

      setUploadFeedback({
        kind: 'confirming',
        message: 'Finalizing upload and queueing processing...',
        mediaAssetId: initPayload.mediaAssetId,
      })

      const confirmResponse = await fetch(`${UPLOAD_CONFIRM_ENDPOINT}/${initPayload.mediaAssetId}`, {
        method: 'POST',
      })

      if (!confirmResponse.ok) {
        throw new Error(await getApiErrorMessage(confirmResponse, 'Upload confirmation failed'))
      }

      const confirmPayload = (await confirmResponse.json()) as UploadConfirmResponse
      setUploadFeedback({
        kind: 'success',
        message: confirmPayload.message,
        mediaAssetId: confirmPayload.mediaAssetId,
        status: confirmPayload.status,
      })
      setUploadFile(null)
    } catch (uploadError) {
      const message = uploadError instanceof Error ? uploadError.message : 'Unknown upload error.'
      setUploadFeedback({
        kind: 'error',
        message,
      })
    } finally {
      setIsUploading(false)
    }
  }, [uploadFile])

  const handleSelectResult = useCallback((index: number) => {
    const nextResult = results[index]
    if (!nextResult) {
      return
    }

    const seekSeconds = Number(nextResult.timestamp) || 0
    const nextVideoUrl = resolveVideoUrl(nextResult, results)

    if (nextVideoUrl && selectedVideoUrl && nextVideoUrl === selectedVideoUrl && playerRef.current) {
      playerRef.current.seekTo(seekSeconds, 'seconds')
      setPendingSeekSeconds(null)
    } else {
      setPendingSeekSeconds(seekSeconds)
    }

    setSelectedIndex(index)
  }, [results, selectedVideoUrl])

  const handlePlayerReady = useCallback(() => {
    if (pendingSeekSeconds == null || !playerRef.current) {
      return
    }

    playerRef.current.seekTo(pendingSeekSeconds, 'seconds')
    setPendingSeekSeconds(null)
  }, [pendingSeekSeconds])

  const handlePlaybackSeconds = useCallback((seconds: number) => {
    setPlaybackSeconds(seconds)
  }, [])

  return (
    <div className="app-shell">
      <TopBar />
      <SearchHero
        query={query}
        isLoading={isLoading}
        onSubmit={handleSearchSubmit}
        onQueryChange={handleQueryChange}
      />
      <UploadPanel
        selectedFile={uploadFile}
        isUploading={isUploading}
        feedback={uploadFeedback}
        onFileSelected={handleUploadFileSelected}
        onStartUpload={handleUploadStart}
      />

      <main className="content-grid">
        <ResultsPanel
          results={results}
          selectedIndex={selectedIndex}
          error={error}
          onSelectResult={handleSelectResult}
        />
        <PlayerPanel
          playerRef={playerRef}
          selectedResult={selectedResult}
          selectedVideoUrl={selectedVideoUrl}
          playbackSeconds={playbackSeconds}
          onPlayerReady={handlePlayerReady}
          onPlaybackSeconds={handlePlaybackSeconds}
        />
      </main>
    </div>
  )
}

export default App
