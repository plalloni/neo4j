/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.schema.ConstraintCreator;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexCreator;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.Function;
import org.neo4j.helpers.ThisShouldNotHappenError;
import org.neo4j.kernel.api.BaseStatement;
import org.neo4j.kernel.api.SchemaStatement;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.AddIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyIndexedException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.IllegalTokenNameException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.operations.KeyReadOperations;
import org.neo4j.kernel.api.operations.StatementTokenNameLookup;
import org.neo4j.kernel.impl.api.index.IndexDescriptor;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

import static org.neo4j.graphdb.DynamicLabel.label;
import static org.neo4j.graphdb.schema.Schema.IndexState.FAILED;
import static org.neo4j.graphdb.schema.Schema.IndexState.ONLINE;
import static org.neo4j.graphdb.schema.Schema.IndexState.POPULATING;
import static org.neo4j.helpers.collection.Iterables.map;
import static org.neo4j.helpers.collection.IteratorUtil.addToCollection;
import static org.neo4j.helpers.collection.IteratorUtil.asCollection;
import static org.neo4j.helpers.collection.IteratorUtil.single;

public class SchemaImpl implements Schema
{
    private final ThreadToStatementContextBridge ctxProvider;
    private final InternalSchemaActions actions;

    public SchemaImpl( ThreadToStatementContextBridge ctxProvider )
    {
        this.ctxProvider = ctxProvider;
        this.actions = new GDBSchemaActions( ctxProvider );
    }

    @Override
    public IndexCreator indexFor( Label label )
    {
        assertInTransaction();

        return new IndexCreatorImpl( actions, label );
    }

    @Override
    public Iterable<IndexDefinition> getIndexes( final Label label )
    {
        assertInTransaction();

        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            List<IndexDefinition> definitions = new ArrayList<>();
            long labelId = statement.labelGetForName( label.name() );
            if ( labelId == KeyReadOperations.NO_SUCH_LABEL )
            {
                return emptyList();
            }
            addDefinitions( definitions, statement, statement.indexesGetForLabel( labelId ), false );
            addDefinitions( definitions, statement, statement.uniqueIndexesGetForLabel( labelId ), true );
            return definitions;
        }
    }

    @Override
    public Iterable<IndexDefinition> getIndexes()
    {
        assertInTransaction();

        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            List<IndexDefinition> definitions = new ArrayList<>();
            addDefinitions( definitions, statement, statement.indexesGetAll(), false );
            addDefinitions( definitions, statement, statement.uniqueIndexesGetAll(), true );
            return definitions;
        }
    }

    private void addDefinitions( List<IndexDefinition> definitions, final BaseStatement statement,
                                 Iterator<IndexDescriptor> indexes, final boolean constraintIndex )
    {
        addToCollection( map( new Function<IndexDescriptor, IndexDefinition>()
        {
            @Override
            public IndexDefinition apply( IndexDescriptor rule )
            {
                try
                {
                    Label label = label( statement.labelGetName( rule.getLabelId() ) );
                    String propertyKey = statement.propertyKeyGetName( rule.getPropertyKeyId() );
                    return new IndexDefinitionImpl( actions, label, propertyKey, constraintIndex );
                }
                catch ( LabelNotFoundKernelException | PropertyKeyIdNotFoundKernelException e )
                {
                    throw new RuntimeException( e );
                }
            }
        }, indexes ), definitions );
    }

    @Override
    public void awaitIndexOnline( IndexDefinition index, long duration, TimeUnit unit )
    {
        assertInTransaction();

        long timeout = System.currentTimeMillis() + unit.toMillis( duration );
        do
        {
            IndexState state = getIndexState( index );
            switch ( state )
            {
            case ONLINE:
                return;
            case FAILED:
                throw new IllegalStateException( "Index entered a FAILED state. Please see database logs." );
            default:
                try
                {
                    Thread.sleep( 100 );
                }
                catch ( InterruptedException e )
                {   // What to do?
                }
                break;
            }
        } while ( System.currentTimeMillis() < timeout );
        throw new IllegalStateException( "Expected index to come online within a reasonable time." );
    }

    @Override
    public void awaitIndexesOnline( long duration, TimeUnit unit )
    {
        assertInTransaction();

        long millisLeft = TimeUnit.MILLISECONDS.convert( duration, unit );
        Collection<IndexDefinition> onlineIndexes = new ArrayList<>();

        for ( Iterator<IndexDefinition> iter = getIndexes().iterator(); iter.hasNext(); )
        {
            if ( millisLeft < 0 )
                throw new IllegalStateException( "Expected all indexes to come online within a reasonable time."
                                                 + "Indexes brought online: " + onlineIndexes
                                                 + ". Indexes not guaranteed to be online: " + asCollection( iter ) );

            IndexDefinition index = iter.next();
            long millisBefore = System.currentTimeMillis();
            awaitIndexOnline( index, millisLeft, TimeUnit.MILLISECONDS );
            millisLeft -= System.currentTimeMillis() - millisBefore;

            onlineIndexes.add( index );
        }
    }

    @Override
    public IndexState getIndexState( final IndexDefinition index )
    {
        assertInTransaction();

        String propertyKey = single( index.getPropertyKeys() );
        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            long labelId = statement.labelGetForName( index.getLabel().name() );
            long propertyKeyId = statement.propertyKeyGetForName( propertyKey );

            if ( labelId == KeyReadOperations.NO_SUCH_LABEL )
            {
                throw new NotFoundException( format( "Label %s not found", index.getLabel().name() ) );
            }

            if ( propertyKeyId == KeyReadOperations.NO_SUCH_PROPERTY_KEY )
            {
                throw new NotFoundException( format( "Property key %s not found", propertyKey ) );
            }

            IndexDescriptor descriptor = statement.indexesGetForLabelAndPropertyKey( labelId, propertyKeyId );
            InternalIndexState indexState = statement.indexGetState( descriptor );
            switch ( indexState )
            {
                case POPULATING:
                    return POPULATING;
                case ONLINE:
                    return ONLINE;
                case FAILED:
                    return FAILED;
                default:
                    throw new IllegalArgumentException( String.format( "Illegal index state %s", indexState ) );
            }
        }
        catch ( SchemaRuleNotFoundException | IndexNotFoundKernelException e )
        {
            throw new NotFoundException( format( "No index for label %s on property %s",
                    index.getLabel().name(), propertyKey ) );
        }
    }

    @Override
    public String getIndexFailure( IndexDefinition index )
    {
        assertInTransaction();

        String propertyKey = single( index.getPropertyKeys() );
        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            long labelId = statement.labelGetForName( index.getLabel().name() );
            long propertyKeyId = statement.propertyKeyGetForName( propertyKey );

            if ( labelId == KeyReadOperations.NO_SUCH_LABEL )
            {
                throw new NotFoundException( format( "Label %s not found", index.getLabel().name() ) );
            }

            if ( propertyKeyId == KeyReadOperations.NO_SUCH_PROPERTY_KEY )
            {
                throw new NotFoundException( format( "Property key %s not found", propertyKey ) );
            }

            IndexDescriptor indexId = statement.indexesGetForLabelAndPropertyKey( labelId, propertyKeyId );
            return statement.indexGetFailure( indexId );
        }
        catch ( SchemaRuleNotFoundException | IndexNotFoundKernelException e )
        {
            throw new NotFoundException( format( "No index for label %s on property %s",
                    index.getLabel().name(), propertyKey ) );
        }
    }

    @Override
    public ConstraintCreator constraintFor( Label label )
    {
        assertInTransaction();

        return new BaseConstraintCreator( actions, label );
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints()
    {
        assertInTransaction();

        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            Iterator<UniquenessConstraint> constraints = statement.constraintsGetAll();
            return asConstraintDefinitions( statement, constraints );
        }
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints( final Label label )
    {
        assertInTransaction();

        try ( BaseStatement statement = ctxProvider.baseStatement() )
        {
            long labelId = statement.labelGetForName( label.name() );
            if ( labelId == KeyReadOperations.NO_SUCH_LABEL )
            {
                return emptyList();
            }
            Iterator<UniquenessConstraint> constraints = statement.constraintsGetForLabel( labelId );
            return asConstraintDefinitions( statement, constraints );
        }
    }

    private Iterable<ConstraintDefinition> asConstraintDefinitions(
            final BaseStatement statement, Iterator<UniquenessConstraint> constraints )
    {
        Iterator<ConstraintDefinition> definitions =
                map( new Function<UniquenessConstraint, ConstraintDefinition>()
                {
                    @Override
                    public ConstraintDefinition apply( UniquenessConstraint constraint )
                    {
                        long labelId = constraint.label();
                        try
                        {
                            Label label = label( statement.labelGetName( labelId ) );
                            return new PropertyUniqueConstraintDefinition( actions, label,
                                    statement.propertyKeyGetName( constraint.propertyKeyId() ) );
                        }
                        catch ( PropertyKeyIdNotFoundKernelException e )
                        {
                            throw new ThisShouldNotHappenError( "Mattias", "Couldn't find property name for " +
                                                                           constraint.propertyKeyId(), e );
                        }
                        catch ( LabelNotFoundKernelException e )
                        {
                            throw new ThisShouldNotHappenError( "Stefan",
                                                                "Couldn't find label name for label id " +
                                                                labelId, e );
                        }
                    }
                }, constraints );

        // Intentionally iterator over it so that we can close the statement context within this method
        return asCollection( definitions );
    }

    private static class GDBSchemaActions implements InternalSchemaActions
    {

        private final ThreadToStatementContextBridge ctxProvider;
        public GDBSchemaActions( ThreadToStatementContextBridge ctxProvider )
        {
            this.ctxProvider = ctxProvider;
        }

        @Override
        public IndexDefinition createIndexDefinition( Label label, String propertyKey )
        {
            SchemaStatement statement = ctxProvider.schemaStatement();
            try
            {
                long labelId = statement.labelGetOrCreateForName( label.name() );
                long propertyKeyId = statement.propertyKeyGetOrCreateForName( propertyKey );
                statement.indexCreate( labelId, propertyKeyId );
                return new IndexDefinitionImpl( this, label, propertyKey, false );
            }
            catch ( AlreadyIndexedException e )
            {
                throw new ConstraintViolationException(
                        format( "There already exists an index for label '%s' on property '%s'.",
                                label.name(), propertyKey ), e );
            }
            catch ( AlreadyConstrainedException e )
            {
                throw new ConstraintViolationException( format(
                        "Label '%s' and property '%s' have a unique constraint defined on them, so an index is " +
                        "already created that matches this.", label.name(), propertyKey ), e );
            }
            catch ( AddIndexFailureException e )
            {
                throw new ConstraintViolationException(
                        e.getUserMessage( new StatementTokenNameLookup( statement ) ), e );
            }
            catch ( IllegalTokenNameException e )
            {
                throw new IllegalArgumentException( e );
            }
            catch ( TooManyLabelsException e )
            {
                throw new IllegalStateException( e );
            }
            finally
            {
                statement.close();
            }
        }

        @Override
        public void dropIndexDefinitions( Label label, String propertyKey )
        {
            try ( SchemaStatement statement = ctxProvider.schemaStatement() )
            {
                long labelId = statement.labelGetForName( label.name() );
                long propertyKeyId = statement.propertyKeyGetForName( propertyKey );

                if ( labelId != KeyReadOperations.NO_SUCH_LABEL && propertyKeyId != KeyReadOperations
                        .NO_SUCH_PROPERTY_KEY )
                {
                    statement.indexDrop( statement.indexesGetForLabelAndPropertyKey( labelId, propertyKeyId ) );
                }
            }
            catch ( SchemaRuleNotFoundException | DropIndexFailureException e )
            {
                throw new ConstraintViolationException( String.format(
                        "Unable to drop index on label `%s` for property %s.", label.name(), propertyKey ), e );
            }
        }

        @Override
        public ConstraintDefinition createPropertyUniquenessConstraint( Label label, String propertyKey )
        {
            SchemaStatement statement = ctxProvider.schemaStatement();
            try
            {
                long labelId = statement.labelGetOrCreateForName( label.name() );
                long propertyKeyId = statement.propertyKeyGetOrCreateForName( propertyKey );
                statement.uniquenessConstraintCreate( labelId, propertyKeyId );
                return new PropertyUniqueConstraintDefinition( this, label, propertyKey );
            }
            catch ( AlreadyConstrainedException e )
            {
                throw new ConstraintViolationException( format(
                        "Label '%s' and property '%s' have a unique constraint defined on them.",
                        label.name(), propertyKey ), e );
            }
            catch ( CreateConstraintFailureException e )
            {
                throw new ConstraintViolationException(
                        e.getUserMessage( new StatementTokenNameLookup( statement ) ), e );
            }
            catch ( AlreadyIndexedException e )
            {
                throw new ConstraintViolationException(
                        format( "There already exists an index for label '%s' on property '%s'. " +
                                "A constraint cannot be created until the index has been dropped.",
                                label.name(), propertyKey ), e );
            }
            catch ( IllegalTokenNameException e )
            {
                throw new IllegalArgumentException( e );
            }
            catch ( TooManyLabelsException e )
            {
                throw new IllegalStateException( e );
            }
            finally
            {
                statement.close();
            }
        }

        @Override
        public void dropPropertyUniquenessConstraint( Label label, String propertyKey )
        {
            try ( SchemaStatement statement = ctxProvider.schemaStatement() )
            {
                long labelId = statement.labelGetOrCreateForName( label.name() );
                long propertyKeyId = statement.propertyKeyGetOrCreateForName( propertyKey );
                UniquenessConstraint constraint = new UniquenessConstraint( labelId, propertyKeyId );
                statement.constraintDrop( constraint );
            }
            catch ( IllegalTokenNameException | TooManyLabelsException | DropConstraintFailureException e )
            {
                throw new ThisShouldNotHappenError( "Mattias", "Unable to drop property unique constraint" );
            }
        }

        @Override
        public String getUserMessage( KernelException e )
        {
            try ( BaseStatement statement = ctxProvider.baseStatement() )
            {
                return e.getUserMessage( new StatementTokenNameLookup( statement ) );
            }
        }

        @Override
        public void assertInTransaction()
        {
            ctxProvider.assertInTransaction();
        }

    }
    private void assertInTransaction()
    {
        ctxProvider.assertInTransaction();
    }
}
