import { useEffect, useState } from 'react'
import type { CompletedAsset } from '../types/media'

interface ProcessedVideosPanelProps {
    assets: CompletedAsset[]
    isLoading: boolean
    errorMessage: string | null
    activeMediaAssetId: string | null
    onPlayAsset: (asset: CompletedAsset) => void
    deletingMediaAssetId: string | null
    onDeleteAsset: (asset: CompletedAsset) => void
}

const createdAtFormatter = new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
})

function formatCreatedAt(value: string): string {
    const parsed = new Date(value)
    if (Number.isNaN(parsed.getTime())) {
        return 'Unknown processing time'
    }

    return createdAtFormatter.format(parsed)
}

export function ProcessedVideosPanel({
    assets,
    isLoading,
    errorMessage,
    activeMediaAssetId,
    onPlayAsset,
    deletingMediaAssetId,
    onDeleteAsset,
}: ProcessedVideosPanelProps) {
    const [openMenuMediaAssetId, setOpenMenuMediaAssetId] = useState<string | null>(null)

    useEffect(() => {
        const handleDocumentMouseDown = (event: MouseEvent) => {
            const target = event.target as HTMLElement | null
            if (!target?.closest('.processed-options')) {
                setOpenMenuMediaAssetId(null)
            }
        }

        const handleDocumentKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                setOpenMenuMediaAssetId(null)
            }
        }

        document.addEventListener('mousedown', handleDocumentMouseDown)
        document.addEventListener('keydown', handleDocumentKeyDown)

        return () => {
            document.removeEventListener('mousedown', handleDocumentMouseDown)
            document.removeEventListener('keydown', handleDocumentKeyDown)
        }
    }, [])

    useEffect(() => {
        if (!openMenuMediaAssetId) {
            return
        }

        const isActiveAssetStillVisible = assets.some((asset) => asset.mediaAssetId === openMenuMediaAssetId)
        if (!isActiveAssetStillVisible) {
            setOpenMenuMediaAssetId(null)
        }
    }, [assets, openMenuMediaAssetId])

    return (
        <section className="panel processed-panel" aria-live="polite">
            <div className="processed-header">
                <h2>Processed Videos</h2>
                <span className="processed-count">{assets.length}</span>
            </div>

            {isLoading ? <p className="panel-empty">Loading processed videos...</p> : null}

            {!isLoading && errorMessage ? (
                <p className="status-banner error" role="alert">
                    {errorMessage}
                </p>
            ) : null}

            {!isLoading && !errorMessage && assets.length === 0 ? (
                <p className="panel-empty">
                    No completed videos yet. Upload a clip and it will appear here once processing finishes.
                </p>
            ) : null}

            {!isLoading && !errorMessage && assets.length > 0 ? (
                <ul className="processed-grid" role="list">
                    {assets.map((asset) => {
                        const isDeleting = deletingMediaAssetId === asset.mediaAssetId
                        const isMenuOpen = openMenuMediaAssetId === asset.mediaAssetId

                        return (
                            <li
                                className={`processed-card ${activeMediaAssetId === asset.mediaAssetId ? 'selected' : ''}`}
                                key={asset.mediaAssetId}
                            >
                                <div className="processed-options">
                                    <button
                                        type="button"
                                        className="processed-options-button"
                                        aria-label={`More options for ${asset.filename}`}
                                        aria-haspopup="menu"
                                        aria-expanded={isMenuOpen}
                                        disabled={isDeleting}
                                        onClick={() => {
                                            setOpenMenuMediaAssetId((previousValue) =>
                                                previousValue === asset.mediaAssetId ? null : asset.mediaAssetId,
                                            )
                                        }}
                                    >
                                        ...
                                    </button>

                                    {isMenuOpen ? (
                                        <div className="processed-options-menu" role="menu" aria-label="Video actions">
                                            <button
                                                type="button"
                                                role="menuitem"
                                                className="processed-options-item danger"
                                                disabled={isDeleting}
                                                onClick={() => {
                                                    setOpenMenuMediaAssetId(null)
                                                    onDeleteAsset(asset)
                                                }}
                                            >
                                                {isDeleting ? 'Deleting...' : 'Delete'}
                                            </button>
                                        </div>
                                    ) : null}
                                </div>

                                {asset.s3ThumbnailUrl ? (
                                    <img
                                        className="processed-thumb"
                                        src={asset.s3ThumbnailUrl}
                                        alt={`${asset.filename} preview`}
                                        loading="lazy"
                                    />
                                ) : (
                                    <div className="processed-thumb processed-thumb-placeholder">No preview image</div>
                                )}

                                <div className="processed-body">
                                    <p className="processed-file" title={asset.filename}>
                                        {asset.filename}
                                    </p>
                                    <p className="processed-time">{formatCreatedAt(asset.createdAt)}</p>
                                    <div className="processed-actions">
                                        <button
                                            type="button"
                                            className="processed-action-button"
                                            disabled={isDeleting}
                                            onClick={() => onPlayAsset(asset)}
                                        >
                                            Play Here
                                        </button>
                                        <a className="processed-link" href={asset.s3VideoUrl} target="_blank" rel="noreferrer">
                                            Open Video
                                        </a>
                                    </div>
                                </div>
                            </li>
                        )
                    })}
                </ul>
            ) : null}
        </section>
    )
}
