/*
 * Copyright (c) 2019 "GraphFoundation" [https://graphfoundation.org]
 *
 * The included source code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file. *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 */
package org.neo4j.causalclustering.discovery;

import org.neo4j.ssl.SslPolicy;

/**
 * Implement an interface to allow for future expansion from just Hazelcast for clustering. I.E. AKKA, etc.
 */
public interface SecureDiscoveryServiceFactory extends DiscoveryServiceFactory
{
    void setSslPolicy( SslPolicy sslPolicy );
}

