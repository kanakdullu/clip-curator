import type { RefObject } from 'react'
import type ReactPlayerType from 'react-player'
import ReactPlayerImport from 'react-player/lazy'
import type { SearchResult } from '../types/search'
import { formatScore, formatTimestamp } from '../utils/searchUtils'

const ReactPlayerComponent =
    (ReactPlayerImport as unknown as { default?: typeof ReactPlayerType }).default ??
    (ReactPlayerImport as unknown as typeof ReactPlayerType)

interface PlayerPanelProps {
    playerRef: RefObject<ReactPlayerType | null>
    selectedResult: SearchResult | null
    selectedVideoUrl: string | null
    selectedFallbackLabel: string | null
    playbackSeconds: number
    onPlayerReady: () => void
    onPlaybackSeconds: (seconds: number) => void
}

export function PlayerPanel({
    playerRef,
    selectedResult,
    selectedVideoUrl,
    selectedFallbackLabel,
    playbackSeconds,
    onPlayerReady,
    onPlaybackSeconds,
}: PlayerPanelProps) {
    return (
        <section className="player-panel">
            <div className="panel-heading">
                <h2>Playback</h2>
                <p>{selectedResult ? `Asset ${selectedResult.mediaAssetId}` : selectedFallbackLabel ?? 'No selection yet'}</p>
            </div>

            <div className="player-frame">
                {selectedVideoUrl ? (
                    <ReactPlayerComponent
                        ref={playerRef}
                        url={selectedVideoUrl}
                        controls
                        width="100%"
                        height="100%"
                        onReady={onPlayerReady}
                        onProgress={(state) => onPlaybackSeconds(state.playedSeconds)}
                    />
                ) : (
                    <div className="player-placeholder">
                        <p>Select a result with a playable source to begin.</p>
                    </div>
                )}
            </div>

            {selectedResult ? (
                <div className="moment-meta">
                    <div className="meta-pill">Type: {selectedResult.matchType}</div>
                    <div className="meta-pill">Target: {formatTimestamp(Number(selectedResult.timestamp))}</div>
                    <div className="meta-pill">Current: {formatTimestamp(playbackSeconds)}</div>
                    <div className="meta-pill">Score: {formatScore(selectedResult.similarityScore)}</div>
                </div>
            ) : null}

        </section>
    )
}
