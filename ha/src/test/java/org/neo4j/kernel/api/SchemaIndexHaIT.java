/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.kernel.api;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.neo4j.function.Predicates;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.factory.TestHighlyAvailableGraphDatabaseFactory;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema.IndexState;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.kernel.api.IndexCapability;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.kernel.api.TokenNameLookup;
import org.neo4j.internal.kernel.api.schema.IndexProviderDescriptor;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.impl.schema.NativeLuceneFusionIndexProviderFactory20;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexEntryUpdate;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.ha.HighlyAvailableGraphDatabase;
import org.neo4j.kernel.ha.UpdatePuller;
import org.neo4j.kernel.ha.cluster.HighAvailabilityMemberState;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.factory.OperationalMode;
import org.neo4j.kernel.impl.ha.ClusterManager;
import org.neo4j.kernel.impl.ha.ClusterManager.ManagedCluster;
import org.neo4j.kernel.impl.index.schema.ByteBufferFactory;
import org.neo4j.kernel.impl.index.schema.fusion.FusionIndexProvider;
import org.neo4j.kernel.impl.spi.KernelContext;
import org.neo4j.kernel.impl.storemigration.StoreMigrationParticipant;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.storageengine.api.NodePropertyAccessor;
import org.neo4j.storageengine.api.schema.IndexSample;
import org.neo4j.storageengine.api.schema.StoreIndexDescriptor;
import org.neo4j.test.DoubleLatch;
import org.neo4j.test.ha.ClusterRule;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.helpers.collection.Iterables.single;
import static org.neo4j.helpers.collection.Iterators.asSet;
import static org.neo4j.helpers.collection.Iterators.asUniqueSet;
import static org.neo4j.io.fs.FileUtils.deleteRecursively;
import static org.neo4j.kernel.api.index.IndexDirectoryStructure.given;
import static org.neo4j.kernel.impl.ha.ClusterManager.allSeesAllAsAvailable;
import static org.neo4j.kernel.impl.ha.ClusterManager.masterAvailable;

public class SchemaIndexHaIT
{
    @ClassRule
    public static DefaultFileSystemRule fileSystemRule = new DefaultFileSystemRule();
    @Rule
    public ClusterRule clusterRule = new ClusterRule();

    private static final IndexProviderDescriptor CONTROLLED_PROVIDER_DESCRIPTOR = new IndexProviderDescriptor( "controlled", "1.0" );
    private static final Predicate<GraphDatabaseService> IS_MASTER =
            item -> item instanceof HighlyAvailableGraphDatabase && ((HighlyAvailableGraphDatabase) item).isMaster();

    private final String key = "key";
    private final Label label = label( "label" );

    @Test
    public void creatingIndexOnMasterShouldHaveSlavesBuildItAsWell() throws Throwable
    {
        // GIVEN
        ManagedCluster cluster = clusterRule.startCluster();
        HighlyAvailableGraphDatabase master = cluster.getMaster();
        Map<Object,Node> data = createSomeData( master );

        // WHEN
        IndexDefinition index = createIndex( master );
        cluster.sync();

        // THEN
        awaitIndexOnline( index, cluster, data );
    }

    @Test
    public void creatingIndexOnSlaveIsNotAllowed()
    {
        // GIVEN
        ManagedCluster cluster = clusterRule.startCluster();
        HighlyAvailableGraphDatabase slave = cluster.getAnySlave();

        // WHEN
        try
        {
            createIndex( slave );
            fail( "should have thrown exception" );
        }
        catch ( ConstraintViolationException e )
        {
            // expected
        }
    }

    @Test
    public void indexPopulationJobsShouldContinueThroughRoleSwitch() throws Throwable
    {
        // GIVEN a cluster of 3
        ControlledGraphDatabaseFactory dbFactory = new ControlledGraphDatabaseFactory();
        ManagedCluster cluster = clusterRule.withDbFactory( dbFactory )
                .withSharedSetting( GraphDatabaseSettings.default_schema_provider, CONTROLLED_PROVIDER_DESCRIPTOR.name() )
                .startCluster();
        HighlyAvailableGraphDatabase firstMaster = cluster.getMaster();

        // where the master gets some data created as well as an index
        Map<Object,Node> data = createSomeData( firstMaster );
        createIndex( firstMaster );
        //dbFactory.awaitPopulationStarted( firstMaster );
        dbFactory.triggerFinish( firstMaster );

        // Pick a slave, pull the data and the index
        HighlyAvailableGraphDatabase aSlave = cluster.getAnySlave();
        aSlave.getDependencyResolver().resolveDependency( UpdatePuller.class ).pullUpdates();

        // and await the index population to start. It will actually block as long as we want it to
        dbFactory.awaitPopulationStarted( aSlave );

        // WHEN we shut down the master
        cluster.shutdown( firstMaster );

        dbFactory.triggerFinish( aSlave );
        cluster.await( masterAvailable( firstMaster ) );
        // get the new master, which should be the slave we pulled from above
        HighlyAvailableGraphDatabase newMaster = cluster.getMaster();

        // THEN
        assertEquals( "Unexpected new master", aSlave, newMaster );
        try ( Transaction tx = newMaster.beginTx() )
        {
            IndexDefinition index = single( newMaster.schema().getIndexes() );
            awaitIndexOnline( index, newMaster, data );
            tx.success();
        }
        // FINALLY: let all db's finish
        for ( HighlyAvailableGraphDatabase db : cluster.getAllMembers() )
        {
            dbFactory.triggerFinish( db );
        }
    }

    @Test
    public void populatingSchemaIndicesOnMasterShouldBeBroughtOnlineOnSlavesAfterStoreCopy() throws Throwable
    {
        /*
        The master has an index that is currently populating.
        Then a slave comes online and contacts the master to get copies of the store files.
        Because the index is still populating, it won't be copied. Instead the slave will build its own.
        We want to observe that the slave builds an index that eventually comes online.
         */

        // GIVEN
        ControlledGraphDatabaseFactory dbFactory = new ControlledGraphDatabaseFactory( IS_MASTER );

        ManagedCluster cluster = clusterRule.withDbFactory( dbFactory )
                .withSharedSetting( GraphDatabaseSettings.default_schema_provider, NativeLuceneFusionIndexProviderFactory20.DESCRIPTOR.name() )
                .startCluster();

        try
        {
            cluster.await( allSeesAllAsAvailable() );

            HighlyAvailableGraphDatabase slave = cluster.getAnySlave();

            // A slave is offline, and has no store files
            ClusterManager.RepairKit slaveDown = bringSlaveOfflineAndRemoveStoreFiles( cluster, slave );

            // And I create an index on the master, and wait for population to start
            HighlyAvailableGraphDatabase master = cluster.getMaster();
            Map<Object,Node> data = createSomeData( master );
            createIndex( master );
            dbFactory.awaitPopulationStarted( master );

            // WHEN the slave comes online before population has finished on the master
            slave = slaveDown.repair();
            cluster.await( allSeesAllAsAvailable(), 180 );
            cluster.sync();

            // THEN, population should finish successfully on both master and slave
            dbFactory.triggerFinish( master );

            // Check master
            IndexDefinition index;
            try ( Transaction tx = master.beginTx() )
            {
                index = single( master.schema().getIndexes() );
                awaitIndexOnline( index, master, data );
                tx.success();
            }

            // Check slave
            try ( Transaction tx = slave.beginTx() )
            {
                awaitIndexOnline( index, slave, data );
                tx.success();
            }
        }
        finally
        {
            for ( HighlyAvailableGraphDatabase db : cluster.getAllMembers() )
            {
                dbFactory.triggerFinish( db );
            }
        }
    }

    @Test
    public void onlineSchemaIndicesOnMasterShouldBeBroughtOnlineOnSlavesAfterStoreCopy() throws Throwable
    {
        /*
        The master has an index that is online.
        Then a slave comes online and contacts the master to get copies of the store files.
        Because the index is online, it should be copied, and the slave should successfully bring the index online.
         */

        // GIVEN
        ControlledGraphDatabaseFactory dbFactory = new ControlledGraphDatabaseFactory();

        ManagedCluster cluster = clusterRule.withDbFactory( dbFactory )
                .withSharedSetting( GraphDatabaseSettings.default_schema_provider, CONTROLLED_PROVIDER_DESCRIPTOR.name() )
                .startCluster();
        cluster.await( allSeesAllAsAvailable(), 120 );

        HighlyAvailableGraphDatabase slave = cluster.getAnySlave();

        // All slaves in the cluster, except the one I care about, proceed as normal
        proceedAsNormalWithIndexPopulationOnAllSlavesExcept( dbFactory, cluster, slave );

        // A slave is offline, and has no store files
        ClusterManager.RepairKit slaveDown = bringSlaveOfflineAndRemoveStoreFiles( cluster, slave );

        // And I create an index on the master, and wait for population to start
        HighlyAvailableGraphDatabase master = cluster.getMaster();
        Map<Object,Node> data = createSomeData( master );
        createIndex( master );
        dbFactory.awaitPopulationStarted( master );

        // And the population finishes
        dbFactory.triggerFinish( master );
        IndexDefinition index;
        try ( Transaction tx = master.beginTx() )
        {
            index = single( master.schema().getIndexes() );
            awaitIndexOnline( index, master, data );
            tx.success();
        }

        // WHEN the slave comes online after population has finished on the master
        slave = slaveDown.repair();
        cluster.await( allSeesAllAsAvailable() );
        cluster.sync();

        // THEN the index should work on the slave
        dbFactory.triggerFinish( slave );
        try ( Transaction tx = slave.beginTx() )
        {
            awaitIndexOnline( index, slave, data );
            tx.success();
        }
    }

    private void proceedAsNormalWithIndexPopulationOnAllSlavesExcept( ControlledGraphDatabaseFactory dbFactory, ManagedCluster cluster,
            HighlyAvailableGraphDatabase slaveToIgnore )
    {
        for ( HighlyAvailableGraphDatabase db : cluster.getAllMembers() )
        {
            if ( db != slaveToIgnore && db.getInstanceState() == HighAvailabilityMemberState.SLAVE )
            {
                dbFactory.triggerFinish( db );
            }
        }
    }

    @SuppressWarnings( "ResultOfMethodCallIgnored" )
    private ClusterManager.RepairKit bringSlaveOfflineAndRemoveStoreFiles( ManagedCluster cluster, HighlyAvailableGraphDatabase slave ) throws IOException
    {
        ClusterManager.RepairKit slaveDown = cluster.shutdown( slave );

        File databaseDir = slave.databaseLayout().databaseDirectory();
        deleteRecursively( databaseDir );
        databaseDir.mkdir();
        return slaveDown;
    }

    private Map<Object,Node> createSomeData( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Map<Object,Node> result = new HashMap<>();
            for ( int i = 0; i < 10; i++ )
            {
                Node node = db.createNode( label );
                Object propertyValue = i;
                node.setProperty( key, propertyValue );
                result.put( propertyValue, node );
            }
            tx.success();
            return result;
        }
    }

    private IndexDefinition createIndex( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            IndexDefinition index = db.schema().indexFor( label ).on( key ).create();
            tx.success();
            return index;
        }
    }

    private static void awaitIndexOnline( IndexDefinition index, ManagedCluster cluster, Map<Object,Node> expectedDdata ) throws InterruptedException
    {
        for ( GraphDatabaseService db : cluster.getAllMembers() )
        {
            awaitIndexOnline( index, db, expectedDdata );
        }
    }

    private static IndexDefinition reHomedIndexDefinition( GraphDatabaseService db, IndexDefinition definition )
    {
        for ( IndexDefinition candidate : db.schema().getIndexes() )
        {
            if ( candidate.equals( definition ) )
            {
                return candidate;
            }
        }
        throw new NoSuchElementException( "New database doesn't have requested index" );
    }

    private static void awaitIndexOnline( IndexDefinition requestedIndex, GraphDatabaseService db, Map<Object,Node> expectedData ) throws InterruptedException
    {
        try ( Transaction tx = db.beginTx() )
        {
            IndexDefinition index = reHomedIndexDefinition( db, requestedIndex );

            long timeout = System.currentTimeMillis() + SECONDS.toMillis( 120 );
            while ( !indexOnline( index, db ) )
            {
                Thread.sleep( 1 );
                if ( System.currentTimeMillis() > timeout )
                {
                    fail( "Expected index to come online within a reasonable time." );
                }
            }

            assertIndexContents( index, db, expectedData );
            tx.success();
        }
    }

    private static void assertIndexContents( IndexDefinition index, GraphDatabaseService db, Map<Object,Node> expectedData )
    {
        for ( Map.Entry<Object,Node> entry : expectedData.entrySet() )
        {
            assertEquals( asSet( entry.getValue() ),
                    asUniqueSet( db.findNodes( single( index.getLabels() ), single( index.getPropertyKeys() ), entry.getKey() ) ) );
        }
    }

    private static boolean indexOnline( IndexDefinition index, GraphDatabaseService db )
    {
        try
        {
            return db.schema().getIndexState( index ) == IndexState.ONLINE;
        }
        catch ( NotFoundException e )
        {
            return false;
        }
    }

    private static class ControlledIndexPopulator implements IndexPopulator
    {
        private final DoubleLatch latch;
        private final IndexPopulator delegate;

        ControlledIndexPopulator( IndexPopulator delegate, DoubleLatch latch )
        {
            this.delegate = delegate;
            this.latch = latch;
        }

        @Override
        public void create()
        {
            delegate.create();
        }

        @Override
        public void drop()
        {
            delegate.drop();
        }

        @Override
        public void add( Collection<? extends IndexEntryUpdate<?>> updates ) throws IndexEntryConflictException
        {
            delegate.add( updates );
            latch.startAndWaitForAllToStartAndFinish();
        }

        @Override
        public void verifyDeferredConstraints( NodePropertyAccessor nodePropertyAccessor ) throws IndexEntryConflictException
        {
            delegate.verifyDeferredConstraints( nodePropertyAccessor );
        }

        @Override
        public IndexUpdater newPopulatingUpdater( NodePropertyAccessor nodePropertyAccessor )
        {
            return delegate.newPopulatingUpdater( nodePropertyAccessor );
        }

        @Override
        public void close( boolean populationCompletedSuccessfully )
        {
            delegate.close( populationCompletedSuccessfully );
            assertTrue( "Expected population to succeed :(", populationCompletedSuccessfully );
            latch.finish();
        }

        @Override
        public void markAsFailed( String failure )
        {
            delegate.markAsFailed( failure );
        }

        @Override
        public void includeSample( IndexEntryUpdate<?> update )
        {
            delegate.includeSample( update );
        }

        @Override
        public IndexSample sampleResult()
        {
            return delegate.sampleResult();
        }
    }

    private static class ControlledIndexProvider extends IndexProvider
    {
        private final IndexProvider delegate;
        private final DoubleLatch latch = new DoubleLatch();

        ControlledIndexProvider( IndexProvider delegate )
        {
            super( CONTROLLED_PROVIDER_DESCRIPTOR, given( delegate.directoryStructure() ) );
            this.delegate = delegate;
        }

        @Override
        public IndexPopulator getPopulator( StoreIndexDescriptor descriptor, IndexSamplingConfig samplingConfig, ByteBufferFactory bufferFactory,
                                            TokenNameLookup tokenNameLookup )
        {
            IndexPopulator populator = delegate.getPopulator( descriptor, samplingConfig, bufferFactory, tokenNameLookup );
            return new ControlledIndexPopulator( populator, latch );
        }

        @Override
        public IndexAccessor getOnlineAccessor( StoreIndexDescriptor descriptor, IndexSamplingConfig samplingConfig, TokenNameLookup tokenNameLookup )
                throws IOException
        {
            return delegate.getOnlineAccessor( descriptor, samplingConfig, tokenNameLookup );
        }

        @Override
        public InternalIndexState getInitialState( StoreIndexDescriptor descriptor )
        {
            return delegate.getInitialState( descriptor );
        }

        @Override
        public IndexCapability getCapability( StoreIndexDescriptor descriptor )
        {
            return delegate.getCapability( descriptor );
        }

        @Override
        public StoreMigrationParticipant storeMigrationParticipant( FileSystemAbstraction fs, PageCache pageCache )
        {
            return delegate.storeMigrationParticipant( fs, pageCache );
        }

        @Override
        public String getPopulationFailure( StoreIndexDescriptor descriptor ) throws IllegalStateException
        {
            return delegate.getPopulationFailure( descriptor );
        }
    }

    interface IndexProviderDependencies
    {
        GraphDatabaseService db();
        Config config();
        PageCache pageCache();
        RecoveryCleanupWorkCollector recoveryCleanupWorkCollector();
    }

    private static class ControllingIndexProviderFactory extends KernelExtensionFactory<IndexProviderDependencies>
    {
        private final Map<GraphDatabaseService,IndexProvider> perDbIndexProvider;
        private final Predicate<GraphDatabaseService> injectLatchPredicate;

        ControllingIndexProviderFactory( Map<GraphDatabaseService,IndexProvider> perDbIndexProvider, Predicate<GraphDatabaseService> injectLatchPredicate )
        {
            super( ExtensionType.DATABASE, CONTROLLED_PROVIDER_DESCRIPTOR.getKey() );
            this.perDbIndexProvider = perDbIndexProvider;
            this.injectLatchPredicate = injectLatchPredicate;
        }

        @Override
        public Lifecycle newInstance( KernelContext context, SchemaIndexHaIT.IndexProviderDependencies deps )
        {
            PageCache pageCache = deps.pageCache();
            File databaseDirectory = context.directory();
            DefaultFileSystemAbstraction fs = fileSystemRule.get();
            IndexProvider.Monitor monitor = IndexProvider.Monitor.EMPTY;
            Config config = deps.config();
            OperationalMode operationalMode = context.databaseInfo().operationalMode;
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector = deps.recoveryCleanupWorkCollector();

            FusionIndexProvider fusionIndexProvider = NativeLuceneFusionIndexProviderFactory20.create( pageCache, databaseDirectory, fs, monitor,
                    config, operationalMode, recoveryCleanupWorkCollector );

            if ( injectLatchPredicate.test( deps.db() ) )
            {
                ControlledIndexProvider provider = new ControlledIndexProvider( fusionIndexProvider );
                perDbIndexProvider.put( deps.db(), provider );
                return provider;
            }
            else
            {
                return fusionIndexProvider;
            }
        }
    }

    private static class ControlledGraphDatabaseFactory extends TestHighlyAvailableGraphDatabaseFactory
    {
        final Map<GraphDatabaseService,IndexProvider> perDbIndexProvider = new ConcurrentHashMap<>();
        private final KernelExtensionFactory<?> factory;

        ControlledGraphDatabaseFactory()
        {
            this( Predicates.alwaysTrue() );
        }

        private ControlledGraphDatabaseFactory( Predicate<GraphDatabaseService> dbsToControlIndexingOn )
        {
            factory = new ControllingIndexProviderFactory( perDbIndexProvider, dbsToControlIndexingOn );
            getCurrentState().removeKernelExtensions( kef -> kef.getClass().getSimpleName().contains( "IndexProvider" ) );
            getCurrentState().addKernelExtensions( Collections.singletonList( factory ) );
        }

        @Override
        public GraphDatabaseBuilder newEmbeddedDatabaseBuilder( File file )
        {
            return super.newEmbeddedDatabaseBuilder( file );
        }

        void awaitPopulationStarted( GraphDatabaseService db )
        {
            ControlledIndexProvider provider = (ControlledIndexProvider) perDbIndexProvider.get( db );
            if ( provider != null )
            {
                provider.latch.waitForAllToStart();
            }
        }

        void triggerFinish( GraphDatabaseService db )
        {
            ControlledIndexProvider provider = (ControlledIndexProvider) perDbIndexProvider.get( db );
            if ( provider != null )
            {
                provider.latch.finish();
            }
        }
    }
}
