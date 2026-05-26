import { memo } from 'react'

function TopBarComponent() {
    return (
        <header className="topbar">
            <div className="brand-mark">
                <span className="brand-dot" aria-hidden="true"></span>
                <p className="brand-name">ClipCurator</p>
            </div>
            <p className="brand-tagline">Multimodal moment search for your private video library</p>
        </header>
    )
}

export const TopBar = memo(TopBarComponent)
