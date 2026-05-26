export interface UploadInitRequestBody {
    filename: string
    mimeType: string
    sizeInBytes: number
}

export interface UploadInitResponse {
    mediaAssetId: string
    uploadUrl: string
}

export interface UploadConfirmResponse {
    mediaAssetId: string
    status: string
    message: string
}

export type UploadFeedbackKind = 'uploading' | 'confirming' | 'success' | 'error'

export interface UploadFeedback {
    kind: UploadFeedbackKind
    message: string
    mediaAssetId?: string
    status?: string
}
