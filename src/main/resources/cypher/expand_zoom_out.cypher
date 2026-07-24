// TODO: relational-style parentId property join — prefer the HAS_PARENT edge:
//   MATCH (child:ContentElement {id: $id})-[:HAS_PARENT]->(parent)
// Kept for now; see DrivineStore.expandByZoomOut.
MATCH (child:ContentElement {id: $id})
WHERE child.parentId IS NOT NULL
MATCH (parent:ContentElement {id: child.parentId})
RETURN {
  id: parent.id,
  uri: parent.uri,
  text: parent.text,
  parentId: parent.parentId,
  ingestionDate: parent.ingestionTimestamp,
  labels: labels(parent),
  properties: properties(parent)
} AS result