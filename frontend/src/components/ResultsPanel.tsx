import { useMemo, useState } from 'react'
import type { CompletedAsset } from '../types/media'
import type { SearchResult } from '../types/search'
import { ResultCard } from './ResultCard'

type ResultFilter = 'all' | 'audio' | 'visual'

interface IndexedResult {
    index: number
    result: SearchResult
}

const RESULT_FILTER_OPTIONS: Array<{ value: ResultFilter; label: string }> = [
    { value: 'all', label: 'All' },
    { value: 'audio', label: 'Audio' },
    { value: 'visual', label: 'Visual' },
]

interface ResultsPanelProps {
    results: SearchResult[]
    completedAssets: CompletedAsset[]
    selectedIndex: number | null
    error: string | null
    onSelectResult: (index: number) => void
}

export function ResultsPanel({ results, completedAssets, selectedIndex, error, onSelectResult }: ResultsPanelProps) {
    const [resultFilter, setResultFilter] = useState<ResultFilter>('all')

    const fallbackThumbnailByAssetId = new Map(
        completedAssets
            .filter((asset) => Boolean(asset.s3ThumbnailUrl))
            .map((asset) => [asset.mediaAssetId, asset.s3ThumbnailUrl]),
    )

    const visibleResults = useMemo(() => {
        return results
            .map((result, index): IndexedResult => ({ index, result }))
            .filter(({ result }) => resultFilter === 'all' || result.matchType === resultFilter)
    }, [resultFilter, results])

    const resultGroups = useMemo(() => {
        const audioMatches = visibleResults.filter(({ result }) => result.matchType === 'audio')
        const visualMatches = visibleResults.filter(({ result }) => result.matchType === 'visual')

        if (resultFilter === 'audio') {
            return [
                {
                    key: 'audio',
                    title: 'Audio matches',
                    entries: audioMatches,
                },
            ]
        }

        if (resultFilter === 'visual') {
            return [
                {
                    key: 'visual',
                    title: 'Visual matches',
                    entries: visualMatches,
                },
            ]
        }

        return [
            {
                key: 'audio',
                title: 'Audio matches',
                entries: audioMatches,
            },
            {
                key: 'visual',
                title: 'Visual matches',
                entries: visualMatches,
            },
        ].filter((group) => group.entries.length > 0)
    }, [resultFilter, visibleResults])

    const emptyMessage =
        resultFilter === 'all'
            ? 'Run a search to see curated moments.'
            : `No ${resultFilter} matches in the current results.`

    return (
        <section className="results-panel">
            <div className="panel-heading results-heading">
                <div className="results-heading-main">
                    <h2>Ranked Moments</h2>
                    <div className="results-filter" role="group" aria-label="Filter ranked moments by match type">
                        {RESULT_FILTER_OPTIONS.map((option) => (
                            <button
                                key={option.value}
                                type="button"
                                className={`results-filter-button ${resultFilter === option.value ? 'active' : ''}`}
                                onClick={() => setResultFilter(option.value)}
                                aria-pressed={resultFilter === option.value}
                            >
                                {option.label}
                            </button>
                        ))}
                    </div>
                </div>
                <p>{visibleResults.length} of {results.length} result(s)</p>
            </div>

            {error ? <p className="status-banner error">{error}</p> : null}

            {!error && visibleResults.length === 0 ? (
                <div className="empty-state">
                    <p>{emptyMessage}</p>
                </div>
            ) : null}

            {!error && visibleResults.length > 0 ? (
                <div className="results-groups">
                    {resultGroups.map((group) => (
                        <section key={group.key} className="results-group">
                            {resultFilter === 'all' ? (
                                <div className="results-group-heading">
                                    <h3>{group.title}</h3>
                                    <span>{group.entries.length}</span>
                                </div>
                            ) : null}

                            <ul className="results-grid results-grid-grouped">
                                {group.entries.map(({ result, index }) => (
                                    <ResultCard
                                        key={`${result.mediaAssetId}-${result.matchType}-${index}`}
                                        index={index}
                                        result={result}
                                        fallbackThumbnailUrl={fallbackThumbnailByAssetId.get(result.mediaAssetId) ?? null}
                                        isSelected={selectedIndex === index}
                                        onSelect={onSelectResult}
                                    />
                                ))}
                            </ul>
                        </section>
                    ))}
                </div>
            ) : null}
        </section>
    )
}
