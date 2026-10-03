MATCH (from:$($entityNodeName) {id: $fromId})
WHERE $fromType IN labels(from)
MATCH (to:$($entityNodeName) {id: $toId})
WHERE $toType IN labels(to)
CREATE (from)-[r:$($relType)]->(to)
$($setClause)
RETURN type(r) AS relationType