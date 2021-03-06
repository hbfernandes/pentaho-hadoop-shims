/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2017 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.big.data.api.cluster.service.locator.impl;

import org.pentaho.big.data.api.cluster.NamedCluster;
import org.pentaho.big.data.api.cluster.service.locator.NamedClusterServiceFactory;
import org.pentaho.big.data.api.cluster.service.locator.NamedClusterServiceLocator;
import org.pentaho.big.data.api.initializer.ClusterInitializationException;
import org.pentaho.big.data.api.initializer.ClusterInitializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by bryan on 11/5/15.
 */
public class NamedClusterServiceLocatorImpl implements NamedClusterServiceLocator {
  public static final String SERVICE_RANKING = "service.ranking";
  //private final Multimap<Class<?>, ServiceFactoryAndRanking<?>> serviceFactoryMap;
  private final Map<String, Map<Class<?>, List<ServiceFactoryAndRanking<?>>>> serviceVendorTypeMapping;
  private final ReadWriteLock readWriteLock;
  private final ClusterInitializer clusterInitializer;

  public NamedClusterServiceLocatorImpl( ClusterInitializer clusterInitializer ) {
    this.clusterInitializer = clusterInitializer;
    readWriteLock = new ReentrantReadWriteLock();
    //    serviceFactoryMap =
    //      Multimaps.newSortedSetMultimap( new HashMap<Class<?>, Collection<ServiceFactoryAndRanking<?>>>(),
    //        () -> new TreeSet<>( ( o1, o2 ) -> {
    //          if ( o1.ranking == o2.ranking ) {
    //            return o1.namedClusterServiceFactory.toString().compareTo( o2.namedClusterServiceFactory.toString() );
    //          }
    //          return o2.ranking - o1.ranking;
    //        } ) );
    serviceVendorTypeMapping =
      new HashMap<>();
  }

  //  @VisibleForTesting Multimap<Class<?>, ServiceFactoryAndRanking<?>> getServiceFactoryMap() {
  //    //return serviceFactoryMap;
  //  }

  public void factoryAdded( NamedClusterServiceFactory<?> namedClusterServiceFactory, Map properties ) {
    if ( namedClusterServiceFactory == null ) {
      return;
    }
    Class<?> serviceClass = namedClusterServiceFactory.getServiceClass();
    Lock writeLock = readWriteLock.writeLock();
    try {
      writeLock.lock();
      String shim = (String) properties.get( "shim" );
      serviceVendorTypeMapping.putIfAbsent( shim, new HashMap<>() );
      Map<Class<?>, List<ServiceFactoryAndRanking<?>>> classListMap = serviceVendorTypeMapping.get( shim );
      classListMap.putIfAbsent( serviceClass, new ArrayList<>() );
      List<ServiceFactoryAndRanking<?>> serviceFactoryAndRankings = classListMap.get( serviceClass );
      serviceFactoryAndRankings.add( new ServiceFactoryAndRanking( (Integer) properties.get( SERVICE_RANKING ),
         namedClusterServiceFactory ) );
    } finally {
      writeLock.unlock();
    }
  }

  public void factoryRemoved( NamedClusterServiceFactory<?> namedClusterServiceFactory, Map properties ) {
    if ( namedClusterServiceFactory == null ) {
      return;
    }
    Class<?> serviceClass = namedClusterServiceFactory.getServiceClass();
    Lock writeLock = readWriteLock.writeLock();
    try {
      writeLock.lock();
      String shim = (String) properties.get( "shim" );
      Optional.ofNullable(serviceVendorTypeMapping.get( shim )).ifPresent( v -> Optional.ofNullable(v.get( serviceClass)).ifPresent( v1 -> v1.remove( namedClusterServiceFactory ) ) );
    } finally {
      writeLock.unlock();
    }
  }

  @Override public <T> T getService( NamedCluster namedCluster, Class<T> serviceClass )
    throws ClusterInitializationException {
    Lock readLock = readWriteLock.readLock();
    try {
      readLock.lock();
      String shim = namedCluster.getShimIdentifier();
      Collection<ServiceFactoryAndRanking<?>> serviceFactoryAndRankings = serviceVendorTypeMapping.computeIfPresent( shim, (key, value) -> value ).computeIfPresent( serviceClass, (key2, value2) -> value2 );
      if ( serviceFactoryAndRankings != null ) {
        for ( ServiceFactoryAndRanking<?> serviceFactoryAndRanking : serviceFactoryAndRankings ) {
          if ( serviceFactoryAndRanking.namedClusterServiceFactory.canHandle( namedCluster ) ) {
            return serviceClass.cast( serviceFactoryAndRanking.namedClusterServiceFactory.create( namedCluster ) );
          }
        }
      }
    } finally {
      readLock.unlock();
    }
    return null;
  }

  static class ServiceFactoryAndRanking<T> {
    final int ranking;
    final NamedClusterServiceFactory<T> namedClusterServiceFactory;

    ServiceFactoryAndRanking( Integer ranking, NamedClusterServiceFactory<T> namedClusterServiceFactory ) {
      if ( ranking == null ) {
        this.ranking = 0;
      } else {
        this.ranking = ranking;
      }
      this.namedClusterServiceFactory = namedClusterServiceFactory;
    }
  }
}
