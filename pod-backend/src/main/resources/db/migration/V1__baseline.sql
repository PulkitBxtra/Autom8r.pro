-- The schema as it stood when migrations were introduced (F2, 2026-09-30), taken from the
-- database Hibernate's ddl-auto had built until then. Existing databases are marked as already at
-- this version (spring.flyway.baseline-on-migrate); an empty one is created from it.
--
-- All pods share this database; pod-backend runs every migration, the other pods only validate.
-- Add changes as new files (V2__what_changes.sql ...), never by editing one already applied.

CREATE TABLE public.action (
    id character varying(255) NOT NULL,
    app_name character varying(255),
    name character varying(255),
    parameters jsonb,
    sorting_order integer,
    type character varying(255),
    workflow_id character varying(255)
);

CREATE TABLE public.app (
    id character varying(255) NOT NULL,
    name character varying(255)
);

CREATE TABLE public.app_action (
    id character varying(255) NOT NULL,
    app_name character varying(255),
    name character varying(255),
    type character varying(255),
    app_id character varying(255)
);

CREATE TABLE public.app_trigger (
    id character varying(255) NOT NULL,
    app_name character varying(255),
    name character varying(255),
    app_id character varying(255)
);

CREATE TABLE public.app_user (
    id character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255),
    password character varying(255)
);

CREATE TABLE public.connection (
    id character varying(255) NOT NULL,
    app_id character varying(255) NOT NULL,
    auth_type character varying(255) NOT NULL,
    created_at bigint,
    credentials text NOT NULL,
    expires_at bigint,
    label character varying(255),
    last_error text,
    last_used_at bigint,
    scopes character varying(255),
    status character varying(255) NOT NULL,
    updated_at bigint,
    user_id character varying(255) NOT NULL,
    last_refreshed_at bigint,
    next_refresh_at bigint,
    refresh_failures integer,
    oauth_client_id character varying(255)
);

CREATE TABLE public.execution_run (
    id character varying(255) NOT NULL,
    end_timestamp bigint,
    metadata jsonb,
    start_timestamp bigint,
    status character varying(255),
    workflow_id character varying(255),
    workflow_version_id character varying(255),
    execution_run_outbox_id character varying(255)
);

CREATE TABLE public.execution_run_outbox (
    id character varying(255) NOT NULL,
    execution_id character varying(255),
    execution_run_id character varying(255)
);

CREATE TABLE public.oauth_client (
    id character varying(255) NOT NULL,
    client_id character varying(255) NOT NULL,
    client_secret text NOT NULL,
    created_at bigint,
    name character varying(255) NOT NULL,
    provider character varying(255) NOT NULL,
    updated_at bigint,
    user_id character varying(255) NOT NULL
);

CREATE TABLE public.oauth_state (
    id character varying(255) NOT NULL,
    app_id character varying(255) NOT NULL,
    code_verifier text NOT NULL,
    connection_id character varying(255),
    created_at bigint NOT NULL,
    provider character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    oauth_client_id character varying(255)
);

CREATE TABLE public.revoked_token (
    token_hash character varying(255) NOT NULL,
    revoked_at bigint
);

CREATE TABLE public.step_run (
    id character varying(255) NOT NULL,
    active_parents integer NOT NULL,
    attempt integer NOT NULL,
    created_at bigint,
    ended_at bigint,
    error text,
    input jsonb,
    node_id character varying(255) NOT NULL,
    output jsonb,
    pending_deps integer NOT NULL,
    run_id character varying(255) NOT NULL,
    started_at bigint,
    status character varying(255) NOT NULL,
    next_attempt_at bigint,
    ready_at bigint,
    first_started_at bigint,
    uncertain boolean,
    CONSTRAINT step_run_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'RETRY_WAIT'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE public.step_task_outbox (
    id character varying(255) NOT NULL,
    created_at bigint NOT NULL,
    run_id character varying(255) NOT NULL,
    step_run_id character varying(255) NOT NULL
);

CREATE TABLE public.trigger (
    id character varying(255) NOT NULL,
    app_name character varying(255),
    name character varying(255),
    parameters jsonb,
    app_trigger_id character varying(255)
);

CREATE TABLE public.trigger_delivery (
    id character varying(255) NOT NULL,
    received_at bigint,
    subscription_id character varying(255)
);

CREATE TABLE public.trigger_subscription (
    id character varying(255) NOT NULL,
    app_id character varying(255) NOT NULL,
    config jsonb,
    connection_id character varying(255),
    created_at bigint,
    external_id character varying(255),
    last_error text,
    last_event_at bigint,
    secret text,
    status character varying(255) NOT NULL,
    trigger_id character varying(255) NOT NULL,
    updated_at bigint,
    user_id character varying(255) NOT NULL,
    workflow_id character varying(255) NOT NULL,
    meta jsonb,
    routing_key character varying(255)
);

CREATE TABLE public.workflow (
    id character varying(255) NOT NULL,
    current_version_id character varying(255),
    name character varying(255),
    trigger_id character varying(255),
    user_id character varying(255),
    active boolean
);

CREATE TABLE public.workflow_version (
    id character varying(255) NOT NULL,
    created_at bigint,
    graph jsonb NOT NULL,
    version integer NOT NULL,
    workflow_id character varying(255) NOT NULL
);

ALTER TABLE ONLY public.action
    ADD CONSTRAINT action_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_action
    ADD CONSTRAINT app_action_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app
    ADD CONSTRAINT app_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_trigger
    ADD CONSTRAINT app_trigger_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT connection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.execution_run_outbox
    ADD CONSTRAINT execution_run_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.execution_run
    ADD CONSTRAINT execution_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.trigger_subscription
    ADD CONSTRAINT idx_trigger_subscription_workflow UNIQUE (workflow_id);

ALTER TABLE ONLY public.oauth_client
    ADD CONSTRAINT oauth_client_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.oauth_state
    ADD CONSTRAINT oauth_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.revoked_token
    ADD CONSTRAINT revoked_token_pkey PRIMARY KEY (token_hash);

ALTER TABLE ONLY public.step_run
    ADD CONSTRAINT step_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.step_task_outbox
    ADD CONSTRAINT step_task_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.trigger_delivery
    ADD CONSTRAINT trigger_delivery_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.trigger
    ADD CONSTRAINT trigger_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.trigger_subscription
    ADD CONSTRAINT trigger_subscription_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uk1j9d9a06i600gd43uu3km82jw UNIQUE (email);

ALTER TABLE ONLY public.execution_run_outbox
    ADD CONSTRAINT ukd8m17381arbltkc55g8kjijl5 UNIQUE (execution_run_id);

ALTER TABLE ONLY public.workflow_version
    ADD CONSTRAINT ukkqr0u03vbieos5f4278yn4mo5 UNIQUE (workflow_id, version);

ALTER TABLE ONLY public.execution_run
    ADD CONSTRAINT ukquveltc41cve6anu5u83neh91 UNIQUE (execution_run_outbox_id);

ALTER TABLE ONLY public.step_run
    ADD CONSTRAINT ukruy4lht62p6d61nqc6pk1mq4t UNIQUE (run_id, node_id);

ALTER TABLE ONLY public.workflow
    ADD CONSTRAINT workflow_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workflow_version
    ADD CONSTRAINT workflow_version_pkey PRIMARY KEY (id);

CREATE INDEX idx_connection_status_expires ON public.connection USING btree (status, expires_at);

CREATE INDEX idx_connection_user_app ON public.connection USING btree (user_id, app_id);

CREATE INDEX idx_execution_run_workflow_start ON public.execution_run USING btree (workflow_id, start_timestamp);

CREATE INDEX idx_oauth_client_user_provider ON public.oauth_client USING btree (user_id, provider);

CREATE INDEX idx_step_run_status_next_attempt ON public.step_run USING btree (status, next_attempt_at);

CREATE INDEX idx_trigger_delivery_received ON public.trigger_delivery USING btree (received_at);

CREATE INDEX idx_trigger_subscription_routing ON public.trigger_subscription USING btree (app_id, routing_key);

ALTER TABLE ONLY public.execution_run
    ADD CONSTRAINT fk8cq3gxprykr6238fvp6lmti0c FOREIGN KEY (execution_run_outbox_id) REFERENCES public.execution_run_outbox(id);

ALTER TABLE ONLY public.app_trigger
    ADD CONSTRAINT fkbu7peh0umtjrvbh5p64g7lqf4 FOREIGN KEY (app_id) REFERENCES public.app(id);

ALTER TABLE ONLY public.trigger
    ADD CONSTRAINT fkdprwhk1rasyfn9srnwueqoipc FOREIGN KEY (app_trigger_id) REFERENCES public.app_trigger(id);

ALTER TABLE ONLY public.action
    ADD CONSTRAINT fkebc4idqthcspy75g2qgfca9y3 FOREIGN KEY (workflow_id) REFERENCES public.workflow(id);

ALTER TABLE ONLY public.execution_run_outbox
    ADD CONSTRAINT fkl53j0nquax44xs07mg4iy3a06 FOREIGN KEY (execution_run_id) REFERENCES public.execution_run(id);

ALTER TABLE ONLY public.app_action
    ADD CONSTRAINT fkpvd9wa3ol3ycpitgb95ie01dw FOREIGN KEY (app_id) REFERENCES public.app(id);
