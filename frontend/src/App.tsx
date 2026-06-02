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
import type { SearchResult, SearchResultGroup } from './types/search'
import type {
  UploadConfirmResponse,
  UploadInitRequestBody,
  UploadInitResponse,
} from './types/upload'
import { resolveVideoUrl } from './utils/searchUtils'
import { getApiErrorMessage, resolveUploadMimeType, uploadFileWithProgress } from './utils/uploadUtils'
import './App.css'

const SEARCH_ENDPOINT = '/api/v1/search'
const COMPLETED_ASSETS_ENDPOINT = '/api/v1/assets/completed?limit=24'
const DELETE_ASSET_ENDPOINT = '/api/v1/assets'
const ASSET_STATUS_STREAM_ENDPOINT = '/api/v1/assets'
const UPLOAD_INIT_ENDPOINT = '/api/v1/upload/init'
const UPLOAD_CONFIRM_ENDPOINT = '/api/v1/upload/confirm'
const TOAST_TERMINAL_DISMISS_MS = 3200

type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
type ToastKind = 'info' | 'success' | 'error'
type UploadToastStage = 'initializing' | 'uploading' | 'confirming' | 'processing' | 'success' | 'error'

interface ProcessingStatusEvent {
  mediaAssetId: string
  status: ProcessingStatus
  timestamp: string
  message: string
}

interface ToastMessage {
  id: string
  kind: ToastKind
  stage: UploadToastStage
  title: string
  message: string
  mediaAssetId?: string
  progressPercent?: number
}

const flattenSearchGroups = (groups: SearchResultGroup[]): SearchResult[] => {
  const flattenedResults: SearchResult[] = []

  for (const group of groups) {
    const groupMatches = [group.bestAudioMatch, group.bestVisualMatch]
      .filter((match): match is SearchResult => match !== null)
      .sort((left, right) => right.similarityScore - left.similarityScore)

    flattenedResults.push(...groupMatches)
  }

  return flattenedResults
}

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
  const [toasts, setToasts] = useState<ToastMessage[]>([])
  const [playbackSeconds, setPlaybackSeconds] = useState(0)
  const [pendingSeekSeconds, setPendingSeekSeconds] = useState<number | null>(null)
  const playerRef = useRef<ReactPlayer | null>(null)
  const statusStreamsRef = useRef<Map<string, EventSource>>(new Map())
  const latestStatusByAssetIdRef = useRef<Map<string, ProcessingStatus>>(new Map())
  const toastTimeoutsRef = useRef<Map<string, number>>(new Map())
  const toastIdByAssetIdRef = useRef<Map<string, string>>(new Map())
  const pendingUploadToastIdRef = useRef<string | null>(null)

  const clearToastTimeout = (toastId: string) => {
    const timeoutId = toastTimeoutsRef.current.get(toastId)
    if (timeoutId !== undefined) {
      window.clearTimeout(timeoutId)
      toastTimeoutsRef.current.delete(toastId)
    }
  }

  const dismissToast = (toastId: string) => {
    clearToastTimeout(toastId)

    if (pendingUploadToastIdRef.current === toastId) {
      pendingUploadToastIdRef.current = null
    }

    for (const [mediaAssetId, mappedToastId] of toastIdByAssetIdRef.current.entries()) {
      if (mappedToastId === toastId) {
        toastIdByAssetIdRef.current.delete(mediaAssetId)
      }
    }

    setToasts((previousToasts) => previousToasts.filter((toast) => toast.id !== toastId))
  }

  const scheduleTerminalToastDismiss = (toastId: string) => {
    clearToastTimeout(toastId)

    const timeoutId = window.setTimeout(() => {
      dismissToast(toastId)
    }, TOAST_TERMINAL_DISMISS_MS)

    toastTimeoutsRef.current.set(toastId, timeoutId)
  }

  const upsertToast = (toast: ToastMessage, options?: { terminal?: boolean }) => {
    setToasts((previousToasts) => {
      const existingIndex = previousToasts.findIndex((previousToast) => previousToast.id === toast.id)

      if (existingIndex >= 0) {
        const nextToasts = [...previousToasts]
        nextToasts[existingIndex] = toast
        return nextToasts
      }

      return [...previousToasts, toast]
    })

    if (toast.mediaAssetId) {
      toastIdByAssetIdRef.current.set(toast.mediaAssetId, toast.id)
    }

    if (options?.terminal) {
      scheduleTerminalToastDismiss(toast.id)
      return
    }

    clearToastTimeout(toast.id)
  }

  const upsertAssetToast = (
    mediaAssetId: string,
    toast: Omit<ToastMessage, 'id' | 'mediaAssetId'>,
    options?: { terminal?: boolean },
  ) => {
    const toastId =
      toastIdByAssetIdRef.current.get(mediaAssetId) ??
      pendingUploadToastIdRef.current ??
      window.crypto.randomUUID()

    if (pendingUploadToastIdRef.current === toastId) {
      pendingUploadToastIdRef.current = null
    }

    upsertToast({
      id: toastId,
      mediaAssetId,
      ...toast,
    }, options)

    return toastId
  }

  const closeStatusStream = (mediaAssetId: string) => {
    const stream = statusStreamsRef.current.get(mediaAssetId)
    if (stream) {
      stream.close()
      statusStreamsRef.current.delete(mediaAssetId)
    }

    latestStatusByAssetIdRef.current.delete(mediaAssetId)
  }

  const loadCompletedAssets = async (showLoadingState = true) => {
    if (showLoadingState) {
      setIsCompletedAssetsLoading(true)
    }
    setCompletedAssetsError(null)

    try {
      const response = await fetch(COMPLETED_ASSETS_ENDPOINT)
      if (!response.ok) {
        throw new Error(`Could not load processed videos (${response.status}).`)
      }

      const payload = (await response.json()) as CompletedAsset[]
      setCompletedAssets(payload)
    } catch (loadError) {
      const message =
        loadError instanceof Error
          ? loadError.message
          : 'Could not load processed videos right now.'

      setCompletedAssets([])
      setCompletedAssetsError(message)
    } finally {
      if (showLoadingState) {
        setIsCompletedAssetsLoading(false)
      }
    }
  }

  const handleProcessingStatusEvent = (mediaAssetId: string, payload: ProcessingStatusEvent) => {
    if (payload.mediaAssetId !== mediaAssetId) {
      return
    }

    const previousStatus = latestStatusByAssetIdRef.current.get(mediaAssetId)
    if (previousStatus === payload.status) {
      return
    }

    latestStatusByAssetIdRef.current.set(mediaAssetId, payload.status)

    const defaultMessage =
      payload.status === 'COMPLETED'
        ? 'Video processing complete'
        : payload.status === 'FAILED'
          ? 'Video processing failed'
          : 'Video processing'

    if (payload.status === 'COMPLETED') {
      upsertAssetToast(
        mediaAssetId,
        {
          kind: 'success',
          stage: 'success',
          title: 'Video ready',
          message: payload.message || defaultMessage,
          progressPercent: 100,
        },
        { terminal: true },
      )
      void loadCompletedAssets(false)
      closeStatusStream(mediaAssetId)
      return
    }

    if (payload.status === 'FAILED') {
      upsertAssetToast(
        mediaAssetId,
        {
          kind: 'error',
          stage: 'error',
          title: 'Processing failed',
          message: payload.message || defaultMessage,
        },
        { terminal: true },
      )
      closeStatusStream(mediaAssetId)
      return
    }

    upsertAssetToast(mediaAssetId, {
      kind: 'info',
      stage: 'processing',
      title: 'Processing video',
      message: payload.message || defaultMessage,
      progressPercent: 100,
    })

    if (payload.status === 'PENDING') {
      latestStatusByAssetIdRef.current.delete(mediaAssetId)
    }
  }

  const openStatusStream = (mediaAssetId: string) => {
    if (statusStreamsRef.current.has(mediaAssetId)) {
      return
    }

    const stream = new EventSource(`${ASSET_STATUS_STREAM_ENDPOINT}/${mediaAssetId}/status-stream`)
    statusStreamsRef.current.set(mediaAssetId, stream)

    stream.addEventListener('asset-status', (event) => {
      if (!(event instanceof MessageEvent)) {
        return
      }

      try {
        const payload = JSON.parse(event.data as string) as ProcessingStatusEvent
        handleProcessingStatusEvent(mediaAssetId, payload)
      } catch {
        // Ignore malformed SSE payloads.
      }
    })

    stream.onerror = () => {
      if (!statusStreamsRef.current.has(mediaAssetId)) {
        return
      }

      upsertAssetToast(
        mediaAssetId,
        {
          kind: 'error',
          stage: 'error',
          title: 'Processing updates disconnected',
          message: 'Live processing updates disconnected. Upload state may be stale.',
        },
        { terminal: true },
      )
      closeStatusStream(mediaAssetId)
    }
  }

  useEffect(() => {
    void loadCompletedAssets()
  }, [])

  useEffect(() => {
    return () => {
      for (const stream of statusStreamsRef.current.values()) {
        stream.close()
      }
      statusStreamsRef.current.clear()
      latestStatusByAssetIdRef.current.clear()
      toastIdByAssetIdRef.current.clear()
      pendingUploadToastIdRef.current = null

      for (const timeoutId of toastTimeoutsRef.current.values()) {
        window.clearTimeout(timeoutId)
      }
      toastTimeoutsRef.current.clear()
    }
  }, [])

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

      const payload = (await response.json()) as SearchResultGroup[]
      const flattenedResults = flattenSearchGroups(payload)
      setResults(flattenedResults)

      if (flattenedResults.length === 0) {
        setSelectedProcessedAsset(null)
        setSelectedIndex(null)
        setPendingSeekSeconds(null)
        setPlaybackSeconds(0)
        return
      }

      const firstPlayable = flattenedResults.findIndex((item) => resolveVideoUrl(item, flattenedResults) !== null)
      const initialIndex = firstPlayable >= 0 ? firstPlayable : 0
      setSelectedProcessedAsset(null)
      setSelectedIndex(initialIndex)
      setPendingSeekSeconds(Number(flattenedResults[initialIndex].timestamp) || 0)
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
  }

  const handleUploadStart = useCallback(async () => {
    if (!uploadFile) {
      upsertToast(
        {
          id: window.crypto.randomUUID(),
          kind: 'error',
          stage: 'error',
          title: 'No file selected',
          message: 'Select a supported video or image file before starting upload.',
        },
        { terminal: true },
      )
      return
    }

    const mimeType = resolveUploadMimeType(uploadFile)
    if (!mimeType) {
      upsertToast(
        {
          id: window.crypto.randomUUID(),
          kind: 'error',
          stage: 'error',
          title: 'Unsupported file type',
          message: 'Supported uploads: MP4, MOV, JPG, PNG, GIF, BMP, WEBP, HEIC, HEIF, AVIF, TIFF.',
        },
        { terminal: true },
      )
      return
    }

    const lifecycleToastId = window.crypto.randomUUID()
    let mediaAssetId: string | null = null

    try {
      setIsUploading(true)
      pendingUploadToastIdRef.current = lifecycleToastId

      upsertToast({
        id: lifecycleToastId,
        kind: 'info',
        stage: 'initializing',
        title: 'Preparing upload',
        message: 'Requesting secure upload URL...',
        progressPercent: 0,
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
      mediaAssetId = initPayload.mediaAssetId
      upsertAssetToast(initPayload.mediaAssetId, {
        kind: 'info',
        stage: 'uploading',
        title: 'Uploading file',
        message: 'Uploading video to storage...',
        progressPercent: 5,
      })

      await uploadFileWithProgress(initPayload.uploadUrl, uploadFile, mimeType, (fraction) => {
        const boundedFraction = Math.min(1, Math.max(0, fraction))
        const mappedProgress = Math.round(5 + boundedFraction * 90)
        const clampedProgress = Math.max(5, Math.min(95, mappedProgress))

        upsertAssetToast(initPayload.mediaAssetId, {
          kind: 'info',
          stage: 'uploading',
          title: 'Uploading file',
          message: 'Uploading video to storage...',
          progressPercent: clampedProgress,
        })
      })

      upsertAssetToast(initPayload.mediaAssetId, {
        kind: 'info',
        stage: 'confirming',
        title: 'Finalizing upload',
        message: 'Upload complete. Queueing video processing...',
        progressPercent: 100,
      })

      const confirmResponse = await fetch(`${UPLOAD_CONFIRM_ENDPOINT}/${initPayload.mediaAssetId}`, {
        method: 'POST',
      })

      if (!confirmResponse.ok) {
        throw new Error(await getApiErrorMessage(confirmResponse, 'Upload confirmation failed'))
      }

      const confirmPayload = (await confirmResponse.json()) as UploadConfirmResponse
      mediaAssetId = confirmPayload.mediaAssetId

      const confirmedStatus = confirmPayload.status as ProcessingStatus
      if (confirmedStatus === 'COMPLETED') {
        upsertAssetToast(
          confirmPayload.mediaAssetId,
          {
            kind: 'success',
            stage: 'success',
            title: 'Video ready',
            message: confirmPayload.message || 'Video processing complete',
            progressPercent: 100,
          },
          { terminal: true },
        )
        void loadCompletedAssets(false)
      } else if (confirmedStatus === 'FAILED') {
        upsertAssetToast(
          confirmPayload.mediaAssetId,
          {
            kind: 'error',
            stage: 'error',
            title: 'Processing failed',
            message: confirmPayload.message || 'Video processing failed',
            progressPercent: 100,
          },
          { terminal: true },
        )
      } else {
        latestStatusByAssetIdRef.current.set(confirmPayload.mediaAssetId, 'PROCESSING')
        upsertAssetToast(confirmPayload.mediaAssetId, {
          kind: 'info',
          stage: 'processing',
          title: 'Processing video',
          message: confirmPayload.message || 'Analyzing transcript and visual frames...',
          progressPercent: 100,
        })
        openStatusStream(confirmPayload.mediaAssetId)
      }

      setUploadFile(null)
    } catch (uploadError) {
      const message = uploadError instanceof Error ? uploadError.message : 'Unknown upload error.'

      if (mediaAssetId) {
        upsertAssetToast(
          mediaAssetId,
          {
            kind: 'error',
            stage: 'error',
            title: 'Upload failed',
            message,
          },
          { terminal: true },
        )
      } else {
        pendingUploadToastIdRef.current = null
        upsertToast(
          {
            id: lifecycleToastId,
            kind: 'error',
            stage: 'error',
            title: 'Upload failed',
            message,
          },
          { terminal: true },
        )
      }
    } finally {
      setIsUploading(false)
    }
  }, [loadCompletedAssets, openStatusStream, uploadFile])

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

  const handleDeleteProcessedAsset = async (asset: CompletedAsset) => {
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

      closeStatusStream(asset.mediaAssetId)
      const activeToastId = toastIdByAssetIdRef.current.get(asset.mediaAssetId)
      if (activeToastId) {
        dismissToast(activeToastId)
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
  }

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
          completedAssets={completedAssets}
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

      <section className="toast-stack" aria-live="polite" aria-atomic="false">
        {toasts.map((toast) => (
          <article key={toast.id} className={`toast-item toast-${toast.kind}`} role="status">
            <div className="toast-main">
              <span className={`toast-icon toast-icon-${toast.kind}`} aria-hidden="true">
                {toast.kind === 'success' ? '✓' : toast.kind === 'error' ? '!' : '↑'}
              </span>
              <div className="toast-content">
                <p className="toast-title">{toast.title}</p>
                <p className="toast-message">{toast.message}</p>
                {toast.stage === 'uploading' || toast.stage === 'confirming' || toast.stage === 'initializing' ? (
                  <div className="toast-progress" aria-hidden="true">
                    <div className="toast-progress-track">
                      <div
                        className="toast-progress-bar"
                        style={{ width: `${Math.max(0, Math.min(100, toast.progressPercent ?? 0))}%` }}
                      />
                    </div>
                    <span className="toast-progress-value">
                      {Math.max(0, Math.min(100, Math.round(toast.progressPercent ?? 0)))}%
                    </span>
                  </div>
                ) : null}
                {toast.stage === 'processing' ? (
                  <div className="toast-processing" aria-hidden="true">
                    <span className="toast-processing-dot" />
                    Processing in background
                  </div>
                ) : null}
              </div>
            </div>
            {toast.stage === 'success' || toast.stage === 'error' ? (
              <button
                type="button"
                className="toast-dismiss"
                onClick={() => dismissToast(toast.id)}
                aria-label="Dismiss notification"
              >
                x
              </button>
            ) : null}
          </article>
        ))}
      </section>
    </div>
  )
}

export default App
