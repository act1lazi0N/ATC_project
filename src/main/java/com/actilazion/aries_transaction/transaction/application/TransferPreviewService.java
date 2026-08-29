package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewResponse;

public interface TransferPreviewService {
    TransferPreviewResponse create(TransferPreviewRequest request, String initiatorEmail);
}
