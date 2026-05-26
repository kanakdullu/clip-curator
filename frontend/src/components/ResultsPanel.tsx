import { memo } from 'react'
import type { SearchResult } from '../types/search'
import { ResultCard } from './ResultCard'

interface ResultsPanelProps {
    results: SearchResult[]
    selectedIndex: number | null
    error: string | null
    onSelectResult: (index: number) => void
}

function ResultsPanelComponent({ results, selectedIndex, error, onSelectResult }: ResultsPanelProps) {
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
                        isSelected={selectedIndex === index}
                        onSelect={onSelectResult}
                    />
                ))}
            </ul>
        </section>
    )
}

export const ResultsPanel = memo(ResultsPanelComponent)
