package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.dto.requests.TransferRequest;
import com.actilazion.aries_transaction.dto.responses.TransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TransferService {
    TransactionResponse transfer(TransferRequest request, String initiatorEmail);

    TransactionResponse getById(UUID txId);

    Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable);
}
