SELECT c1 FROM t1 WHERE NOT EXISTS (SELECT c1 FROM t2 WHERE t1.c3 = 4 AND t1.c1 = t2.c2)
