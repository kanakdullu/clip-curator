import type { ChangeEvent } from 'react'
import { formatFileSize } from '../utils/uploadUtils'

interface UploadPanelProps {
    selectedFile: File | null
    isUploading: boolean
    onFileSelected: (file: File | null) => void
    onStartUpload: () => void
}

export function UploadPanel({
    selectedFile,
    isUploading,
    onFileSelected,
    onStartUpload,
}: UploadPanelProps) {
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
                <p>Video or image, max 2 GB</p>
            </div>

            <div className="upload-controls">
                <label className="upload-input-wrap">
                    <span className="upload-input-label">Select file</span>
                    <input
                        type="file"
                        accept="video/mp4,video/quicktime,image/jpeg,image/png,image/gif,image/bmp,image/webp,image/heic,image/heif,image/avif,image/tiff,.mp4,.mov,.jpg,.jpeg,.png,.gif,.bmp,.webp,.heic,.heif,.avif,.tif,.tiff"
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
                    {isUploading ? 'Uploading...' : 'Upload Media'}
                </button>
            </div>

            {selectedFile ? (
                <p className="upload-file-meta">
                    {selectedFile.name} | {formatFileSize(selectedFile.size)}
                </p>
            ) : (
                <p className="upload-hint">Pick a supported video or image file to publish and queue processing.</p>
            )}
        </section>
    )
}
