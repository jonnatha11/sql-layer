SELECT customers.name,order_date,sku,quan FROM customers INNER JOIN orders ON customers.cid = orders.cid INNER JOIN items ON orders.oid = items.oid WHERE items.sku < '8888'
