-- ShopVault schema. Portable between PostgreSQL (production) and H2 in PostgreSQL mode (tests).
-- Money is stored as integer minor units (e.g. sen); all timestamps are UTC.

create table users (
    id                    uuid                     primary key,
    email                 varchar(254)             not null,
    password_hash         varchar(255)             not null,
    full_name             varchar(100)             not null,
    role                  varchar(20)              not null,
    enabled               boolean                  not null,
    failed_login_attempts integer                  not null,
    locked_until          timestamp with time zone,
    token_version         integer                  not null,
    created_at            timestamp with time zone not null,
    updated_at            timestamp with time zone not null,
    version               bigint                   not null,
    constraint uq_users_email unique (email),
    constraint ck_users_role check (role in ('CUSTOMER', 'ADMIN'))
);

create table refresh_tokens (
    id         uuid                     primary key,
    user_id    uuid                     not null references users (id) on delete cascade,
    token_hash varchar(64)              not null,
    family_id  uuid                     not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint uq_refresh_tokens_hash unique (token_hash)
);
create index ix_refresh_tokens_user on refresh_tokens (user_id);
create index ix_refresh_tokens_family on refresh_tokens (family_id);
create index ix_refresh_tokens_expires on refresh_tokens (expires_at);

create table categories (
    id         uuid                     primary key,
    name       varchar(80)              not null,
    slug       varchar(80)              not null,
    created_at timestamp with time zone not null,
    constraint uq_categories_slug unique (slug)
);

create table products (
    id          uuid                     primary key,
    sku         varchar(64)              not null,
    name        varchar(200)             not null,
    description varchar(4000),
    price_cents bigint                   not null,
    stock       integer                  not null,
    active      boolean                  not null,
    category_id uuid                     references categories (id),
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null,
    version     bigint                   not null,
    constraint uq_products_sku unique (sku),
    constraint ck_products_price check (price_cents >= 0),
    constraint ck_products_stock check (stock >= 0)
);
create index ix_products_category on products (category_id);
create index ix_products_active_created on products (active, created_at);

create table cart_items (
    id         uuid                     primary key,
    user_id    uuid                     not null references users (id) on delete cascade,
    product_id uuid                     not null references products (id),
    quantity   integer                  not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_cart_items_user_product unique (user_id, product_id),
    constraint ck_cart_items_quantity check (quantity > 0)
);

create table orders (
    id                uuid                     primary key,
    user_id           uuid                     not null references users (id),
    status            varchar(30)              not null,
    currency          varchar(3)               not null,
    subtotal_cents    bigint                   not null,
    shipping_cents    bigint                   not null,
    total_cents       bigint                   not null,
    idempotency_key   varchar(64)              not null,
    payment_reference varchar(100),
    ship_name         varchar(100)             not null,
    ship_phone        varchar(20)              not null,
    ship_line1        varchar(200)             not null,
    ship_line2        varchar(200),
    ship_city         varchar(100)             not null,
    ship_state        varchar(100)             not null,
    ship_postcode     varchar(10)              not null,
    ship_country      varchar(2)               not null,
    created_at        timestamp with time zone not null,
    updated_at        timestamp with time zone not null,
    paid_at           timestamp with time zone,
    version           bigint                   not null,
    constraint uq_orders_user_idempotency unique (user_id, idempotency_key),
    constraint uq_orders_payment_reference unique (payment_reference),
    constraint ck_orders_status check (status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    constraint ck_orders_totals check (subtotal_cents >= 0 and shipping_cents >= 0
                                       and total_cents = subtotal_cents + shipping_cents)
);
create index ix_orders_user_created on orders (user_id, created_at);
create index ix_orders_status_created on orders (status, created_at);

create table order_items (
    id               uuid         primary key,
    order_id         uuid         not null references orders (id) on delete cascade,
    line_no          integer      not null,
    product_id       uuid         not null references products (id),
    sku              varchar(64)  not null,
    product_name     varchar(200) not null,
    unit_price_cents bigint       not null,
    quantity         integer      not null,
    line_total_cents bigint       not null,
    constraint uq_order_items_line unique (order_id, line_no),
    constraint ck_order_items_quantity check (quantity > 0),
    constraint ck_order_items_total check (line_total_cents = unit_price_cents * quantity)
);

create table payment_events (
    event_id    varchar(100)             primary key,
    order_id    uuid                     references orders (id),
    event_type  varchar(50)              not null,
    outcome     varchar(40)              not null,
    received_at timestamp with time zone not null
);

create table audit_log (
    id          uuid                     primary key,
    occurred_at timestamp with time zone not null,
    actor_id    uuid,
    action      varchar(50)              not null,
    target      varchar(200),
    client_ip   varchar(45),
    detail      varchar(500)
);
create index ix_audit_log_occurred on audit_log (occurred_at);
create index ix_audit_log_action on audit_log (action, occurred_at);
