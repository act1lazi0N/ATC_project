DELETE FROM transfer_previews
WHERE amount < 1000;

ALTER TABLE transfer_previews
    DROP CONSTRAINT chk_transfer_previews_amount;

ALTER TABLE transfer_previews
    ADD CONSTRAINT chk_transfer_previews_amount CHECK (amount >= 1000);

ALTER TABLE account_creation_requests
    ADD COLUMN response_payload JSONB;

UPDATE account_creation_requests request
SET request_hash = encode(
        digest(
            convert_to(
                '17:ACCOUNT_CREATE_V1'
                    || octet_length(convert_to(account.account_type, 'UTF8'))::TEXT || ':' || account.account_type
                    || octet_length(convert_to(account.currency, 'UTF8'))::TEXT || ':' || account.currency
                    || CASE
                        WHEN account.description IS NULL THEN '-1:'
                        ELSE octet_length(convert_to(account.description, 'UTF8'))::TEXT || ':' || account.description
                    END,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
    ),
    response_payload = jsonb_build_object(
        'id', account.id::TEXT,
        'userId', account.user_id::TEXT,
        'accountNumber', account.account_number,
        'accountType', account.account_type,
        'balance', account.balance::TEXT,
        'currency', account.currency,
        'status', account.status,
        'createdAt', to_char(
            account.created_at AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
        ),
        'description', account.description
    )
FROM accounts account
WHERE request.account_id = account.id;

ALTER TABLE account_creation_requests
    ALTER COLUMN response_payload SET NOT NULL;

ALTER TABLE account_creation_requests
    ADD CONSTRAINT chk_account_creation_requests_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_account_creation_response_object
        CHECK (jsonb_typeof(response_payload) = 'object');
