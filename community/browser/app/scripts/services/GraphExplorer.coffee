###!
Copyright (c) 2002-2015 "Neo Technology,"
Network Engine for Objects in Lund AB [http://neotechnology.com]

This file is part of Neo4j.

Neo4j is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
###

'use strict';

angular.module('neo4jApp.services')
  .factory 'GraphExplorer', [
    '$q'
    'Cypher'
    'CypherGraphModel'
    'Settings'
    ($q, Cypher, CypherGraphModel, Settings) ->
      return  {
        exploreNeighboursWithInternalRelationships: (node, graph) ->
          q = $q.defer()
          @exploreNeighbours(node).then (neighboursResult) =>
            if neighboursResult.nodes.length > Settings.maxNeighbours
              return q.reject('Sorry! Too many neighbours')
            graph.addNodes(neighboursResult.nodes.map(CypherGraphModel.convertNode()))
            graph.addRelationships(neighboursResult.relationships.map(CypherGraphModel.convertRelationship(graph)))
            @internalRelationships(graph.nodes()).then (result) =>
              graph.addRelationships(result.relationships.map(CypherGraphModel.convertRelationship(graph)))
              q.resolve()
          q.promise

        exploreNeighbours: (node) ->
          q = $q.defer()
          Cypher.transaction()
          .commit("MATCH (a)-[r]-() WHERE id(a)= #{node.id} RETURN r;")
          .then(q.resolve)
          q.promise

        internalRelationships: (nodes) ->
          q = $q.defer()
          if nodes.length is 0
            q.resolve()
            return q.promise
          ids = nodes.map((node) -> node.id)
          Cypher.transaction()
          .commit("""
            MATCH a -[r]-> b WHERE id(a) IN[#{ids.join(',')}]
            AND id(b) IN[#{ids.join(',')}]
            RETURN r;"""
          )
          .then(q.resolve)
          q.promise
      }
  ]
