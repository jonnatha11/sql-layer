SELECT name FROM child WHERE pid IN (SELECT parent.id FROM parent WHERE parent.name = 'foo')
