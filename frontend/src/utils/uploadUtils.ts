const SUPPORTED_MIME_TYPES = new Set(['video/mp4', 'video/quicktime'])

export const resolveUploadMimeType = (file: File): string | null => {
    const providedMimeType = file.type.toLowerCase()
    if (SUPPORTED_MIME_TYPES.has(providedMimeType)) {
        return providedMimeType
    }

    const normalizedName = file.name.toLowerCase()
    if (normalizedName.endsWith('.mp4')) {
        return 'video/mp4'
    }

    if (normalizedName.endsWith('.mov')) {
        return 'video/quicktime'
    }

    return null
}

export const formatFileSize = (sizeInBytes: number): string => {
    if (!Number.isFinite(sizeInBytes) || sizeInBytes <= 0) {
        return '0 B'
    }

    const units = ['B', 'KB', 'MB', 'GB']
    let value = sizeInBytes
    let unitIndex = 0

    while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024
        unitIndex += 1
    }

    const precision = unitIndex === 0 ? 0 : 1
    return `${value.toFixed(precision)} ${units[unitIndex]}`
}

export const getApiErrorMessage = async (response: Response, fallbackMessage: string): Promise<string> => {
    const fallback = `${fallbackMessage} (status ${response.status}).`

    try {
        const text = await response.text()
        if (!text) {
            return fallback
        }

        const parsed = JSON.parse(text) as { message?: string; error?: string }
        if (parsed.message) {
            return parsed.message
        }

        if (parsed.error) {
            return parsed.error
        }

        return fallback
    } catch {
        return fallback
    }
}

export const uploadFileWithProgress = (
    uploadUrl: string,
    file: File,
    mimeType: string,
    onProgress: (fraction: number) => void,
): Promise<void> => {
    return new Promise((resolve, reject) => {
        const request = new XMLHttpRequest()
        request.open('PUT', uploadUrl)
        request.setRequestHeader('Content-Type', mimeType)

        request.upload.onprogress = (event) => {
            if (!event.lengthComputable || event.total <= 0) {
                return
            }

            onProgress(event.loaded / event.total)
        }

        request.onerror = () => {
            reject(new Error('Upload to storage failed due to a network error.'))
        }

        request.onabort = () => {
            reject(new Error('Upload was canceled.'))
        }

        request.onload = () => {
            if (request.status >= 200 && request.status < 300) {
                onProgress(1)
                resolve()
                return
            }

            reject(new Error(`Upload to storage failed with status ${request.status}.`))
        }

        request.send(file)
    })
}
