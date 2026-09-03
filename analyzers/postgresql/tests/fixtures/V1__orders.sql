CREATE SCHEMA sales;
CREATE TYPE sales.status AS ENUM ('NEW', 'PAID');
CREATE TABLE sales.orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    amount numeric NOT NULL CHECK (amount >= 500),
    status sales.status DEFAULT 'NEW',
    created_at timestamptz DEFAULT now(),
    final_price numeric GENERATED ALWAYS AS (amount * 0.9) STORED,
    customer_id bigint,
    CONSTRAINT order_customer FOREIGN KEY (customer_id) REFERENCES sales.customers(id),
    CONSTRAINT unique_customer_amount UNIQUE (customer_id, amount)
);
CREATE INDEX order_amount_idx ON sales.orders (amount) WHERE amount > 500;
ALTER TABLE sales.orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY order_owner ON sales.orders FOR SELECT TO PUBLIC USING (customer_id = 42);
CREATE VIEW sales.large_orders AS SELECT id, amount FROM sales.orders WHERE amount > 1000;
CREATE MATERIALIZED VIEW sales.totals AS SELECT customer_id, sum(amount) AS amount FROM sales.orders GROUP BY customer_id;
CREATE FUNCTION sales.audit_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO sales.audit(order_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_audit AFTER UPDATE OF status ON sales.orders FOR EACH ROW EXECUTE FUNCTION sales.audit_order();
SELECT o.id, sum(o.amount) FROM sales.orders o JOIN sales.customers c ON c.id = o.customer_id
WHERE o.amount > 500 GROUP BY o.id ORDER BY o.id;
INSERT INTO sales.orders (amount) VALUES (600);
UPDATE sales.orders SET amount = amount + 100 WHERE id = 1;
DELETE FROM sales.orders WHERE id = 2;
