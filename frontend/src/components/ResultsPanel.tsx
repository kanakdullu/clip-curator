import type { CompletedAsset } from '../types/media'
import type { SearchResult } from '../types/search'
import { ResultCard } from './ResultCard'

interface ResultsPanelProps {
    results: SearchResult[]
    completedAssets: CompletedAsset[]
    selectedIndex: number | null
    error: string | null
    onSelectResult: (index: number) => void
}

export function ResultsPanel({ results, completedAssets, selectedIndex, error, onSelectResult }: ResultsPanelProps) {
    const fallbackThumbnailByAssetId = new Map(
        completedAssets
            .filter((asset) => Boolean(asset.s3ThumbnailUrl))
            .map((asset) => [asset.mediaAssetId, asset.s3ThumbnailUrl]),
    )

    return (
        <section className="results-panel">
            <div className="panel-heading">
                <h2>Ranked Moments</h2>
                <p>{results.length} result(s)</p>
            </div>

            {error ? <p className="status-banner error">{error}</p> : null}

            {!error && results.length === 0 ? (
                <div className="empty-state">
                    <p>Run a search to see curated moments.</p>
                </div>
            ) : null}

            <ul className="results-grid">
                {results.map((result, index) => (
                    <ResultCard
                        key={`${result.mediaAssetId}-${index}`}
                        index={index}
                        result={result}
                        fallbackThumbnailUrl={fallbackThumbnailByAssetId.get(result.mediaAssetId) ?? null}
                        isSelected={selectedIndex === index}
                        onSelect={onSelectResult}
                    />
                ))}
            </ul>
        </section>
    )
}
