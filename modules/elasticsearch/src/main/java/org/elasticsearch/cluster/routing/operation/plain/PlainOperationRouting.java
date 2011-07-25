/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.operation.plain;

import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.operation.OperationRouting;
import org.elasticsearch.cluster.routing.operation.hash.HashFunction;
import org.elasticsearch.cluster.routing.operation.hash.djb.DjbHashFunction;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexShardMissingException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndexMissingException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author kimchy (shay.banon)
 */
public class PlainOperationRouting extends AbstractComponent implements OperationRouting {

    private final HashFunction hashFunction;

    private final boolean useType;

    @Inject public PlainOperationRouting(Settings indexSettings, HashFunction hashFunction) {
        super(indexSettings);
        this.hashFunction = hashFunction;
        this.useType = indexSettings.getAsBoolean("cluster.routing.operation.use_type", false);
    }

    @Override public ShardIterator indexShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing) throws IndexMissingException, IndexShardMissingException {
        return shards(clusterState, index, type, id, routing).shardsIt();
    }

    @Override public ShardIterator deleteShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing) throws IndexMissingException, IndexShardMissingException {
        return shards(clusterState, index, type, id, routing).shardsIt();
    }

    @Override public ShardIterator getShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing, @Nullable String preference) throws IndexMissingException, IndexShardMissingException {
        return preferenceActiveShardIterator(shards(clusterState, index, type, id, routing), clusterState.nodes().localNodeId(), preference);
    }

    @Override public ShardIterator getShards(ClusterState clusterState, String index, int shardId, @Nullable String preference) throws IndexMissingException, IndexShardMissingException {
        return preferenceActiveShardIterator(shards(clusterState, index, shardId), clusterState.nodes().localNodeId(), preference);
    }

    @Override public GroupShardsIterator broadcastDeleteShards(ClusterState clusterState, String index) throws IndexMissingException {
        return indexRoutingTable(clusterState, index).groupByShardsIt();
    }

    @Override public GroupShardsIterator deleteByQueryShards(ClusterState clusterState, String index, @Nullable Set<String> routing) throws IndexMissingException {
        if (routing == null || routing.isEmpty()) {
            return indexRoutingTable(clusterState, index).groupByShardsIt();
        }

        // we use set here and not identity set since we might get duplicates
        HashSet<ShardIterator> set = new HashSet<ShardIterator>();
        IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
        for (String r : routing) {
            int shardId = shardId(clusterState, index, null, null, r);
            IndexShardRoutingTable indexShard = indexRouting.shard(shardId);
            if (indexShard == null) {
                throw new IndexShardMissingException(new ShardId(index, shardId));
            }
            set.add(indexShard.shardsRandomIt());
        }
        return new GroupShardsIterator(set);
    }

    @Override public int searchShardsCount(ClusterState clusterState, String[] indices, String[] concreteIndices, @Nullable String queryHint, @Nullable Map<String, Set<String>> routing, @Nullable String preference) throws IndexMissingException {
        if (concreteIndices == null || concreteIndices.length == 0) {
            concreteIndices = clusterState.metaData().concreteAllOpenIndices();
        }
        if (routing != null) {
            HashSet<ShardId> set = new HashSet<ShardId>();
            for (String index : concreteIndices) {
                IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
                Set<String> effectiveRouting = routing.get(index);
                if (effectiveRouting != null) {
                    for (String r : effectiveRouting) {
                        int shardId = shardId(clusterState, index, null, null, r);
                        IndexShardRoutingTable indexShard = indexRouting.shard(shardId);
                        if (indexShard == null) {
                            throw new IndexShardMissingException(new ShardId(index, shardId));
                        }
                        // we might get duplicates, but that's ok, its an estimated count? (we just want to know if its 1 or not)
                        set.add(indexShard.shardId());
                    }
                }
            }
            return set.size();
        } else {
            // we use list here since we know we are not going to create duplicates
            int count = 0;
            for (String index : concreteIndices) {
                IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
                count += indexRouting.shards().size();
            }
            return count;
        }
    }

    @Override public GroupShardsIterator searchShards(ClusterState clusterState, String[] indices, String[] concreteIndices, @Nullable String queryHint, @Nullable Map<String, Set<String>> routing, @Nullable String preference) throws IndexMissingException {
        if (concreteIndices == null || concreteIndices.length == 0) {
            concreteIndices = clusterState.metaData().concreteAllOpenIndices();
        }

        if (routing != null) {
            // we use set here and not list since we might get duplicates
            HashSet<ShardIterator> set = new HashSet<ShardIterator>();
            for (String index : concreteIndices) {
                IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
                Set<String> effectiveRouting = routing.get(index);
                if (effectiveRouting != null) {
                    for (String r : effectiveRouting) {
                        int shardId = shardId(clusterState, index, null, null, r);
                        IndexShardRoutingTable indexShard = indexRouting.shard(shardId);
                        if (indexShard == null) {
                            throw new IndexShardMissingException(new ShardId(index, shardId));
                        }
                        // we might get duplicates, but that's ok, they will override one another
                        set.add(preferenceActiveShardIterator(indexShard, clusterState.nodes().localNodeId(), preference));
                    }
                }
            }
            return new GroupShardsIterator(set);
        } else {
            // we use list here since we know we are not going to create duplicates
            ArrayList<ShardIterator> set = new ArrayList<ShardIterator>();
            for (String index : concreteIndices) {
                IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
                for (IndexShardRoutingTable indexShard : indexRouting) {
                    set.add(preferenceActiveShardIterator(indexShard, clusterState.nodes().localNodeId(), preference));
                }
            }
            return new GroupShardsIterator(set);
        }
    }

    private ShardIterator preferenceActiveShardIterator(IndexShardRoutingTable indexShard, String nodeId, @Nullable String preference) {
        if (preference == null) {
            return indexShard.activeShardsRandomIt();
        }
        if ("_local".equals(preference)) {
            return indexShard.preferNodeShardsIt(nodeId);
        }
        if ("_primary".equals(preference)) {
            return indexShard.primaryShardIt();
        }
        // if not, then use it as the index
        return indexShard.shardsIt(DjbHashFunction.DJB_HASH(preference));
    }

    public IndexMetaData indexMetaData(ClusterState clusterState, String index) {
        IndexMetaData indexMetaData = clusterState.metaData().index(index);
        if (indexMetaData == null) {
            throw new IndexMissingException(new Index(index));
        }
        return indexMetaData;
    }

    protected IndexRoutingTable indexRoutingTable(ClusterState clusterState, String index) {
        IndexRoutingTable indexRouting = clusterState.routingTable().index(index);
        if (indexRouting == null) {
            throw new IndexMissingException(new Index(index));
        }
        return indexRouting;
    }


    // either routing is set, or type/id are set

    protected IndexShardRoutingTable shards(ClusterState clusterState, String index, String type, String id, String routing) {
        int shardId = shardId(clusterState, index, type, id, routing);
        return shards(clusterState, index, shardId);
    }

    protected IndexShardRoutingTable shards(ClusterState clusterState, String index, int shardId) {
        IndexShardRoutingTable indexShard = indexRoutingTable(clusterState, index).shard(shardId);
        if (indexShard == null) {
            throw new IndexShardMissingException(new ShardId(index, shardId));
        }
        return indexShard;
    }

    private int shardId(ClusterState clusterState, String index, String type, @Nullable String id, @Nullable String routing) {
        if (routing == null) {
            if (!useType) {
                return Math.abs(hash(id)) % indexMetaData(clusterState, index).numberOfShards();
            } else {
                return Math.abs(hash(type, id)) % indexMetaData(clusterState, index).numberOfShards();
            }
        }
        return Math.abs(hash(routing)) % indexMetaData(clusterState, index).numberOfShards();
    }

    protected int hash(String routing) {
        return hashFunction.hash(routing);
    }

    protected int hash(String type, String id) {
        if (type == null || "_all".equals(type)) {
            throw new ElasticSearchIllegalArgumentException("Can't route an operation with no type and having type part of the routing (for backward comp)");
        }
        return hashFunction.hash(type, id);
    }
}
