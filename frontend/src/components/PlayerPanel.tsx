import { memo, useCallback } from 'react'
import type { RefObject } from 'react'
import ReactPlayer from 'react-player'
import type { SearchResult } from '../types/search'
import { formatScore, formatTimestamp } from '../utils/searchUtils'

interface PlayerPanelProps {
    playerRef: RefObject<ReactPlayer | null>
    selectedResult: SearchResult | null
    selectedVideoUrl: string | null
    playbackSeconds: number
    onPlayerReady: () => void
    onPlaybackSeconds: (seconds: number) => void
}

function PlayerPanelComponent({
    playerRef,
    selectedResult,
    selectedVideoUrl,
    playbackSeconds,
    onPlayerReady,
    onPlaybackSeconds,
}: PlayerPanelProps) {
    const handleProgress = useCallback(
        (state: { playedSeconds: number }) => {
            onPlaybackSeconds(state.playedSeconds)
        },
        [onPlaybackSeconds],
    )

    return (
        <section className="player-panel">
            <div className="panel-heading">
                <h2>Playback</h2>
                <p>{selectedResult ? `Asset ${selectedResult.mediaAssetId}` : 'No selection yet'}</p>
            </div>

            <div className="player-frame">
                {selectedVideoUrl ? (
                    <ReactPlayer
                        ref={playerRef}
                        url={selectedVideoUrl}
                        controls
                        width="100%"
                        height="100%"
                        onReady={onPlayerReady}
                        onProgress={handleProgress}
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

            {selectedResult?.contentSnippet ? <p className="spoken-line">“{selectedResult.contentSnippet}”</p> : null}
        </section>
    )
}

export const PlayerPanel = memo(PlayerPanelComponent)
