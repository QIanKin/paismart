import type { AxiosProgressEvent } from 'axios';
import { request } from '../request';

export function fetchAgentAttachmentUpload(file: File, onUploadProgress?: (event: AxiosProgressEvent) => void) {
  return request<Api.AgentAsset.Attachment>({
    url: '/agent/assets/attachments',
    method: 'post',
    data: { file },
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    onUploadProgress,
    timeout: 60 * 1000
  });
}

export function fetchAgentImageUpload(file: File, onUploadProgress?: (event: AxiosProgressEvent) => void) {
  return fetchAgentAttachmentUpload(file, onUploadProgress);
}
