SELECT val + 10 FROM (SELECT COUNT(id) AS val FROM t1) AS anon1