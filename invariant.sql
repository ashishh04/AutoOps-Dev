SELECT s.table_name, s.index_name,
       SUM(CASE
             WHEN s.sub_part IS NOT NULL THEN s.sub_part * 4
             WHEN c.data_type IN ('varchar','char') THEN c.character_maximum_length * 4
             WHEN c.data_type IN ('text','mediumtext','longtext','json') THEN 3072
             ELSE 8
           END) AS bytes
FROM information_schema.statistics s
JOIN information_schema.columns c
  ON c.table_schema = s.table_schema
 AND c.table_name   = s.table_name
 AND c.column_name  = s.column_name
WHERE s.table_schema = DATABASE()
GROUP BY s.table_name, s.index_name
HAVING bytes > 3072;
