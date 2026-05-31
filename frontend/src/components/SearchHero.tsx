import type { FormEvent } from 'react'

interface SearchHeroProps {
    query: string
    isLoading: boolean
    onSubmit: (event: FormEvent<HTMLFormElement>) => void
    onQueryChange: (value: string) => void
}

export function SearchHero({ query, isLoading, onSubmit, onQueryChange }: SearchHeroProps) {
    return (
        <section className="hero-panel">
            <h1>Find the exact second a scene happens</h1>
            <p>
                Search conceptually across spoken dialogue and visual action, then jump to the precise
                timestamp.
            </p>

            <form className="search-form" onSubmit={onSubmit}>
                <input
                    type="text"
                    value={query}
                    onChange={(event) => onQueryChange(event.target.value)}
                    placeholder="Try: 'deploying to aws' or 'playing basketball'"
                    aria-label="Search video moments"
                />
                <button type="submit" disabled={isLoading}>
                    {isLoading ? 'Searching…' : 'Search Moments'}
                </button>
            </form>
        </section>
    )
}
