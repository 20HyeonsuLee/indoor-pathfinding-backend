#!/bin/bash
# 길찾기 테스트 스크립트
BUILDING_ID="3d4ec427-b699-48a4-9df8-052cd60593f2"

curl -s -X POST \
  "http://localhost:8080/api/v1/buildings/${BUILDING_ID}/pathfinding" \
  -H "Content-Type: application/json" \
  -d "{\"startFloorLevel\":1,\"startX\":0.0,\"startY\":0.0,\"destinationName\":\"컴공세미나실\",\"preference\":\"SHORTEST\"}" \
  | python3 -m json.tool
