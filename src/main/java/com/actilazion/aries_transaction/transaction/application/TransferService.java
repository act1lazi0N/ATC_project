package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import com.actilazion.aries_transaction.transaction.dto.TransferExecuteRequest;

public interface TransferService {
    TransactionResponse transfer(TransferRequest request, String initiatorEmail);

    TransactionResponse execute(TransferExecuteRequest request, String initiatorEmail);

    TransactionResponse reverse(UUID originalTransactionId, ReversalRequest request, String initiatorEmail);

    TransactionResponse refund(UUID originalTransactionId, RefundRequest request, String initiatorEmail);

    TransactionResponse getById(UUID txId, String requesterEmail);

    Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable, String requesterEmail);
}
