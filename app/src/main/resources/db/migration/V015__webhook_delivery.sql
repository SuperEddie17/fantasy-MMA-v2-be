-- Webhook deliveries already handled (plan.md F5.4): a redelivered id is acknowledged and ignored.
CREATE TABLE webhook_delivery (
    source      TEXT        NOT NULL,
    delivery_id TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, delivery_id)
);
