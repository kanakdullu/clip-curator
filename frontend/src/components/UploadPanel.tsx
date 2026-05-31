import type { ChangeEvent } from 'react'
import type { UploadFeedback } from '../types/upload'
import { formatFileSize } from '../utils/uploadUtils'

interface UploadPanelProps {
    selectedFile: File | null
    isUploading: boolean
    uploadProgressPercent: number
    feedback: UploadFeedback | null
    onFileSelected: (file: File | null) => void
    onStartUpload: () => void
}

export function UploadPanel({
    selectedFile,
    isUploading,
    uploadProgressPercent,
    feedback,
    onFileSelected,
    onStartUpload,
}: UploadPanelProps) {
    const clampedProgress = Math.max(0, Math.min(100, uploadProgressPercent))
    const showProgress = isUploading || clampedProgress > 0 || feedback?.kind === 'success'

    const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
        const nextFile = event.target.files?.[0] ?? null
        onFileSelected(nextFile)

        // Clear the input value so selecting the same file again still triggers change.
        event.target.value = ''
    }

    return (
        <section className="upload-panel">
            <div className="panel-heading upload-heading">
                <h2>Upload Clip</h2>
                <p>MP4 or MOV, max 2 GB</p>
            </div>

            <div className="upload-controls">
                <label className="upload-input-wrap">
                    <span className="upload-input-label">Select file</span>
                    <input
                        type="file"
                        accept="video/mp4,video/quicktime,.mp4,.mov"
                        onChange={handleFileChange}
                        disabled={isUploading}
                    />
                </label>

                <button
                    type="button"
                    className="upload-button"
                    onClick={onStartUpload}
                    disabled={isUploading || !selectedFile}
                >
                    {isUploading ? 'Uploading...' : 'Upload Video'}
                </button>
            </div>

            {selectedFile ? (
                <p className="upload-file-meta">
                    {selectedFile.name} | {formatFileSize(selectedFile.size)}
                </p>
            ) : (
                <p className="upload-hint">Pick a video file to publish and queue processing.</p>
            )}

            {showProgress ? (
                <div className="upload-progress" role="status" aria-live="polite">
                    <div className="upload-progress-header">
                        <span>Transfer progress</span>
                        <span>{clampedProgress}%</span>
                    </div>
                    <div className="upload-progress-track" aria-hidden="true">
                        <div className="upload-progress-bar" style={{ width: `${clampedProgress}%` }} />
                    </div>
                </div>
            ) : null}

            {feedback ? <p className={`upload-status ${feedback.kind}`}>{feedback.message}</p> : null}

            {feedback?.mediaAssetId ? (
                <p className="upload-asset-meta">
                    Asset ID: {feedback.mediaAssetId}
                    {feedback.status ? ` | Status: ${feedback.status}` : ''}
                </p>
            ) : null}
        </section>
    )
}
