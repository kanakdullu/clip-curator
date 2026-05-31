import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type ReactPlayer from 'react-player'
import { PlayerPanel } from './components/PlayerPanel'
import { ProcessedVideosPanel } from './components/ProcessedVideosPanel'
import { ResultsPanel } from './components/ResultsPanel'
import { SearchHero } from './components/SearchHero'
import { TopBar } from './components/TopBar'
import { UploadPanel } from './components/UploadPanel'
import type { CompletedAsset } from './types/media'
import type { SearchResult } from './types/search'
import type {
  UploadConfirmResponse,
  UploadFeedback,
  UploadInitRequestBody,
  UploadInitResponse,
} from './types/upload'
import { resolveVideoUrl } from './utils/searchUtils'
import { getApiErrorMessage, resolveUploadMimeType, uploadFileWithProgress } from './utils/uploadUtils'
import './App.css'

const SEARCH_ENDPOINT = '/api/v1/search'
const COMPLETED_ASSETS_ENDPOINT = '/api/v1/assets/completed?limit=24'
const DELETE_ASSET_ENDPOINT = '/api/v1/assets'
const UPLOAD_INIT_ENDPOINT = '/api/v1/upload/init'
const UPLOAD_CONFIRM_ENDPOINT = '/api/v1/upload/confirm'

function App() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[]>([])
  const [completedAssets, setCompletedAssets] = useState<CompletedAsset[]>([])
  const [selectedProcessedAsset, setSelectedProcessedAsset] = useState<CompletedAsset | null>(null)
  const [isCompletedAssetsLoading, setIsCompletedAssetsLoading] = useState(false)
  const [completedAssetsError, setCompletedAssetsError] = useState<string | null>(null)
  const [deletingMediaAssetId, setDeletingMediaAssetId] = useState<string | null>(null)
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [uploadFeedback, setUploadFeedback] = useState<UploadFeedback | null>(null)
  const [uploadProgressPercent, setUploadProgressPercent] = useState(0)

  useEffect(() => {
    let cancelled = false

    const loadCompletedAssets = async () => {
      setIsCompletedAssetsLoading(true)
      setCompletedAssetsError(null)

      try {
        const response = await fetch(COMPLETED_ASSETS_ENDPOINT)
        if (!response.ok) {
          throw new Error(`Could not load processed videos (${response.status}).`)
        }

        const payload = (await response.json()) as CompletedAsset[]
        if (cancelled) {
          return
        }

        setCompletedAssets(payload)
      } catch (error) {
        if (cancelled) {
          return
        }

        const message =
          error instanceof Error
            ? error.message
            : 'Could not load processed videos right now.'

        setCompletedAssets([])
        setCompletedAssetsError(message)
      } finally {
        if (!cancelled) {
          setIsCompletedAssetsLoading(false)
        }
      }
    }

    void loadCompletedAssets()

    return () => {
      cancelled = true
    }
  }, [])
  const [playbackSeconds, setPlaybackSeconds] = useState(0)
  const [pendingSeekSeconds, setPendingSeekSeconds] = useState<number | null>(null)
  const playerRef = useRef<ReactPlayer | null>(null)

  const selectedResult = selectedIndex == null ? null : (results[selectedIndex] ?? null)
  const selectedVideoUrl = selectedResult
    ? resolveVideoUrl(selectedResult, results)
    : selectedProcessedAsset?.s3VideoUrl ?? null

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
        setSelectedProcessedAsset(null)
        setSelectedIndex(null)
        setPendingSeekSeconds(null)
        setPlaybackSeconds(0)
        return
      }

      const firstPlayable = payload.findIndex((item) => resolveVideoUrl(item, payload) !== null)
      const initialIndex = firstPlayable >= 0 ? firstPlayable : 0
      setSelectedProcessedAsset(null)
      setSelectedIndex(initialIndex)
      setPendingSeekSeconds(Number(payload[initialIndex].timestamp) || 0)
      setPlaybackSeconds(0)
    } catch (searchError) {
      const message = searchError instanceof Error ? searchError.message : 'Unknown search error.'
      setError(message)
      setResults([])
      setSelectedProcessedAsset(null)
      setSelectedIndex(null)
      setPendingSeekSeconds(null)
      setPlaybackSeconds(0)
    } finally {
      setIsLoading(false)
    }
  }, [])

  const handleSearchSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    const trimmed = query.trim()
    if (!trimmed) {
      setError('Type a concept first, for example: "playing basketball".')
      return
    }

    void executeSearch(trimmed)
  }

  const handleQueryChange = (value: string) => {
    setQuery(value)
  }

  const handleUploadFileSelected = (file: File | null) => {
    setUploadFile(file)
    setUploadFeedback(null)
    setUploadProgressPercent(0)
  }

  const handleUploadStart = useCallback(async () => {
    if (!uploadFile) {
      setUploadProgressPercent(0)
      setUploadFeedback({
        kind: 'error',
        message: 'Select an MP4 or MOV file before starting upload.',
      })
      return
    }

    const mimeType = resolveUploadMimeType(uploadFile)
    if (!mimeType) {
      setUploadProgressPercent(0)
      setUploadFeedback({
        kind: 'error',
        message: 'Only MP4 or MOV uploads are supported by the backend.',
      })
      return
    }

    try {
      setIsUploading(true)
      setUploadProgressPercent(0)
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
      setUploadProgressPercent(5)
      setUploadFeedback({
        kind: 'uploading',
        message: 'Uploading video bytes to object storage...',
        mediaAssetId: initPayload.mediaAssetId,
      })

      await uploadFileWithProgress(initPayload.uploadUrl, uploadFile, mimeType, (fraction) => {
        const boundedFraction = Math.min(1, Math.max(0, fraction))
        const mappedProgress = Math.round(5 + boundedFraction * 90)
        setUploadProgressPercent(Math.max(5, Math.min(95, mappedProgress)))
      })

      setUploadProgressPercent(95)
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
      setUploadProgressPercent(100)
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

  const handleSelectResult = (index: number) => {
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

    setSelectedProcessedAsset(null)
    setSelectedIndex(index)
  }

  const handleSelectProcessedAsset = (asset: CompletedAsset) => {
    const isSameVideo = selectedVideoUrl === asset.s3VideoUrl

    setSelectedIndex(null)
    setSelectedProcessedAsset(asset)
    setPlaybackSeconds(0)

    if (isSameVideo && playerRef.current) {
      playerRef.current.seekTo(0, 'seconds')
      setPendingSeekSeconds(null)
      return
    }

    setPendingSeekSeconds(0)
  }

  const handleDeleteProcessedAsset = useCallback(async (asset: CompletedAsset) => {
    const shouldDelete = window.confirm(`Delete "${asset.filename}" from your library?`)
    if (!shouldDelete) {
      return
    }

    const hadSearchMatch = results.some((result) => result.mediaAssetId === asset.mediaAssetId)
    const wasSelectedProcessed = selectedProcessedAsset?.mediaAssetId === asset.mediaAssetId

    setDeletingMediaAssetId(asset.mediaAssetId)
    setCompletedAssetsError(null)

    try {
      const response = await fetch(`${DELETE_ASSET_ENDPOINT}/${asset.mediaAssetId}`, {
        method: 'DELETE',
      })

      if (!response.ok) {
        throw new Error(await getApiErrorMessage(response, 'Failed to delete video.'))
      }

      setCompletedAssets((previousAssets) =>
        previousAssets.filter((previousAsset) => previousAsset.mediaAssetId !== asset.mediaAssetId),
      )
      setResults((previousResults) =>
        previousResults.filter((previousResult) => previousResult.mediaAssetId !== asset.mediaAssetId),
      )

      if (hadSearchMatch) {
        setSelectedIndex(null)
      }

      if (wasSelectedProcessed) {
        setSelectedProcessedAsset(null)
      }

      if (hadSearchMatch || wasSelectedProcessed) {
        setPendingSeekSeconds(null)
        setPlaybackSeconds(0)
      }
    } catch (deleteError) {
      const message = deleteError instanceof Error ? deleteError.message : 'Failed to delete video.'
      setCompletedAssetsError(message)
    } finally {
      setDeletingMediaAssetId(null)
    }
  }, [results, selectedProcessedAsset])

  const handlePlayerReady = () => {
    if (pendingSeekSeconds == null || !playerRef.current) {
      return
    }

    playerRef.current.seekTo(pendingSeekSeconds, 'seconds')
    setPendingSeekSeconds(null)
  }

  const handlePlaybackSeconds = (seconds: number) => {
    setPlaybackSeconds(seconds)
  }

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
        uploadProgressPercent={uploadProgressPercent}
        feedback={uploadFeedback}
        onFileSelected={handleUploadFileSelected}
        onStartUpload={handleUploadStart}
      />

      <ProcessedVideosPanel
        assets={completedAssets}
        isLoading={isCompletedAssetsLoading}
        errorMessage={completedAssetsError}
        activeMediaAssetId={selectedProcessedAsset?.mediaAssetId ?? null}
        onPlayAsset={handleSelectProcessedAsset}
        deletingMediaAssetId={deletingMediaAssetId}
        onDeleteAsset={handleDeleteProcessedAsset}
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
          selectedFallbackLabel={selectedProcessedAsset ? `Processed ${selectedProcessedAsset.filename}` : null}
          playbackSeconds={playbackSeconds}
          onPlayerReady={handlePlayerReady}
          onPlaybackSeconds={handlePlaybackSeconds}
        />
      </main>
    </div>
  )
}

export default App
