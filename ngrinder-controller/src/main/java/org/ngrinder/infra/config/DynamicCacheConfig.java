/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder.infra.config;

import com.google.common.cache.CacheBuilder;
import com.hazelcast.config.*;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.MessageListener;
import com.hazelcast.spring.cache.HazelcastCacheManager;
import com.hazelcast.spring.context.SpringManagedContext;
import net.grinder.util.NetworkUtils;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.CacheConfiguration.CacheEventListenerFactoryConfiguration;
import net.sf.ehcache.config.Configuration;
import org.ngrinder.common.constant.ClusterConstants;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.infra.hazelcast.topic.message.TopicEvent;
import org.ngrinder.infra.hazelcast.topic.subscriber.TopicSubscriber;
import org.ngrinder.infra.logger.CoreLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.guava.GuavaCache;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.config.MaxSizeConfig.MaxSizePolicy.PER_NODE;
import static net.grinder.util.NetworkUtils.DEFAULT_LOCAL_HOST_ADDRESS;
import static org.ngrinder.common.constant.CacheConstants.*;
import static org.ngrinder.common.util.TypeConvertUtils.cast;
import static org.ngrinder.infra.config.DynamicCacheConfig.CacheConfigHolder.Mode.DIST;
import static org.ngrinder.infra.config.DynamicCacheConfig.CacheConfigHolder.Mode.DIST_AND_LOCAL;
import static org.ngrinder.infra.hazelcast.topic.subscriber.TopicSubscriber.TOPIC_NAME;

/**
 * Dynamic cache configuration. This get the control of EhCache configuration from Spring. Depending
 * on the system.conf, it creates local cache or dist cache.
 *
 * @author Mavlarn
 * @author JunHo Yoon
 * @since 3.1
 */
@org.springframework.context.annotation.Configuration
public class DynamicCacheConfig implements ClusterConstants {

	@Autowired
	private Config config;

	private final int DAY = 24 * 60 * 60;
	private final int HOUR = 60 * 60;
	private final int MIN = 60;

	@Bean
	public org.springframework.cache.CacheManager cacheManager() {
		return new CompositeCacheManager(getLocalCacheManager(), getDistCacheManager());
	}

	private SimpleCacheManager getLocalCacheManager() {
		SimpleCacheManager cacheManager = new SimpleCacheManager();
		List<Cache> caches = new ArrayList<>();
		for (Map.Entry<String, CacheBuilder<Object, Object>> each : cacheConfigMap().getGuavaCacheConfig().entrySet()) {
			caches.add(new GuavaCache(each.getKey(), each.getValue().build()));
		}
		cacheManager.setCaches(caches);
		cacheManager.initializeCaches();
		return cacheManager;
	}

	private HazelcastCacheManager getDistCacheManager() {
		return new HazelcastCacheManager(embeddedHazelcast());
	}

	@Bean
	public SpringManagedContext managedContext() {
		return new SpringManagedContext();
	}

	@Bean
	public HazelcastInstance embeddedHazelcast() {
		com.hazelcast.config.Config hazelcastConfig = new com.hazelcast.config.Config();
		hazelcastConfig.setManagedContext(managedContext());
		hazelcastConfig.getMemberAttributeConfig().setAttributes(getClusterMemberAttributes());
		hazelcastConfig.setMapConfigs(cacheConfigMap().getHazelcastCacheConfigs());
		hazelcastConfig.addExecutorConfig(getExecutorConfig(REGION_EXECUTOR_SERVICE_NAME));
		hazelcastConfig.addExecutorConfig(getExecutorConfig(AGENT_EXECUTOR_SERVICE_NAME));
		hazelcastConfig.addTopicConfig(getTopicConfig());
		NetworkConfig networkConfig = hazelcastConfig.getNetworkConfig();
		networkConfig.setPort(getClusterPort());

		if (getClusterURIs() != null && getClusterURIs().length > 0) {
			JoinConfig join = networkConfig.getJoin();
			join.getAwsConfig().setEnabled(false);
			join.getMulticastConfig().setEnabled(false);
			TcpIpConfig tcpIpConfig = join.getTcpIpConfig();
			tcpIpConfig.setEnabled(true);
			tcpIpConfig.setMembers(Arrays.asList(getClusterURIs()));
			networkConfig.setPublicAddress(getMyPublicAddress(Arrays.asList(getClusterURIs())));
		} else {
			JoinConfig join = networkConfig.getJoin();
			join.getAwsConfig().setEnabled(false);
			join.getTcpIpConfig().setEnabled(false);
			join.getMulticastConfig().setEnabled(true);
		}

		HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
		ITopic<TopicEvent> topic = hazelcastInstance.getTopic(TOPIC_NAME);
		topic.addMessageListener(getTopicSubscriber());
		return hazelcastInstance;
	}

	private ExecutorConfig getExecutorConfig(String regionExecutorService) {
		ExecutorConfig config = new ExecutorConfig();
		config.setName(regionExecutorService);
		return config;
	}

	private TopicConfig getTopicConfig() {
		TopicConfig topicConfig = new TopicConfig();
		topicConfig.setGlobalOrderingEnabled(true);
		topicConfig.setStatisticsEnabled(true);
		topicConfig.setName(TOPIC_NAME);
		return topicConfig;
	}

	private Map<String, Object> getClusterMemberAttributes() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(REGION_ATTR_KEY, config.getRegion());
		return attributes;
	}

	private String getMyPublicAddress(List<String> ips) {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface iface = interfaces.nextElement();
				if (iface.isLoopback() || !iface.isUp())
					continue;

				Enumeration<InetAddress> addresses = iface.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress addr = addresses.nextElement();
					String hostAddress = addr.getHostAddress();
					if (ips.contains(hostAddress)) {
						return hostAddress;
					}
				}
			}
		} catch (SocketException e) {
			throw new NGrinderRuntimeException("error while resolving current ip", e);
		}
		throw new NGrinderRuntimeException("the ip address set doesn't contain current ips");
	}

	@Bean
	public MessageListener<TopicEvent> getTopicSubscriber() {
		return new TopicSubscriber();
	}

	@SuppressWarnings("PointlessArithmeticExpression")
	@Bean
	public CacheConfigHolder cacheConfigMap() {
		CacheConfigHolder cm = new CacheConfigHolder();
		cm.addDistCache(CACHE_SAMPLING, 15, 15 , DIST , 0 , 0);
		cm.addDistCache(CACHE_MONITORING, 15, 15, DIST , 0 , 0);

		cm.addDistCache(CACHE_USERS, 30, 100 , DIST_AND_LOCAL , 30 , 100);
		cm.addDistCache(CACHE_FILE_ENTRIES, 1 * HOUR + 40 * MIN, 100 , DIST_AND_LOCAL, 1 * HOUR + 40 * MIN, 100);

		cm.addLocalCache(CACHE_RIGHT_PANEL_ENTRIES, 1 * DAY, 2);
		cm.addLocalCache(CACHE_LEFT_PANEL_ENTRIES, 1 * DAY, 2);
		cm.addLocalCache(CACHE_CURRENT_PERFTEST_STATISTICS, 5, 1);
		cm.addLocalCache(CACHE_LOCAL_AGENTS, 1 * HOUR, 1);
		return cm;
	}

	static class CacheConfigHolder {
		enum Mode {
			DIST,
			LOCAL,
			DIST_AND_LOCAL,
		}

		private final Map<String, MapConfig> hazelcastCacheConfigs = new ConcurrentHashMap<>();
		private final Map<String, CacheBuilder<Object, Object>> guavaCacheConfig = new ConcurrentHashMap<>();

		void addLocalCache(String cacheName, int timeout, int count) {
			CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder().maximumSize(count).expireAfterWrite(timeout, TimeUnit.SECONDS);
			guavaCacheConfig.put(cacheName, cacheBuilder);
		}

		void addDistCache(String cacheName, int timeout, int count, Mode mode, int nearCacheTimeout, int nearCacheCount) {
			MapConfig mapConfig = new MapConfig(cacheName);
			mapConfig.setTimeToLiveSeconds(timeout);
			mapConfig.setMaxSizeConfig(new MaxSizeConfig(count, PER_NODE));
			if (DIST_AND_LOCAL.equals(mode)) {
				NearCacheConfig nearCacheConfig = new NearCacheConfig(cacheName);
				nearCacheConfig.setTimeToLiveSeconds(nearCacheTimeout);
				nearCacheConfig.setMaxSize(nearCacheCount);
				mapConfig.setNearCacheConfig(nearCacheConfig);
			}
			hazelcastCacheConfigs.put(cacheName, mapConfig);
		}

		Map<String, MapConfig> getHazelcastCacheConfigs() {
			return hazelcastCacheConfigs;
		}

		Map<String, CacheBuilder<Object, Object>> getGuavaCacheConfig() {
			return guavaCacheConfig;
		}
	}

	protected boolean isClustered() {
		return config.isClustered();
	}

	protected String getClusterMode() {
		return config.getClusterProperties().getProperty(PROP_CLUSTER_MODE, "advanced");
	}

	String getCacheName() {
		return "cacheManager";
	}

	protected String[] getClusterURIs() {
		return config.getClusterURIs();
	}

	public String getClusterHostName() {
		String hostName = config.getClusterProperties().getProperty(PROP_CLUSTER_HOST, DEFAULT_LOCAL_HOST_ADDRESS);
		try {
			//noinspection ResultOfMethodCallIgnored
			InetAddress.getByName(hostName);
		} catch (Exception e) {
			CoreLogger.LOGGER.error("The cluster host name {} is not available. Use localhost instead", hostName);
			hostName = "localhost";
		}
		return hostName;
	}

	int getClusterPort() {
		int port = config.getClusterProperties().getPropertyInt(PROP_CLUSTER_PORT);
		try {
			final InetAddress byName = InetAddress.getByName(getClusterHostName());
			port = NetworkUtils.checkPortAvailability(byName, port, 30);
		} catch (Exception e) {
			CoreLogger.LOGGER.error("The cluster port {} is failed to bind. Please check network configuration.", port);
		}
		return port;
	}

	protected List<String> getReplicatedCacheNames(Configuration cacheManagerConfig) {
		Map<String, CacheConfiguration> cacheConfigurations = cacheManagerConfig.getCacheConfigurations();
		List<String> replicatedCacheNames = new ArrayList<String>();
		for (Map.Entry<String, CacheConfiguration> eachConfig : cacheConfigurations.entrySet()) {
			List<CacheEventListenerFactoryConfiguration> list = cast(eachConfig.getValue()
					.getCacheEventListenerConfigurations());
			for (CacheEventListenerFactoryConfiguration each : list) {
				if (each.getFullyQualifiedClassPath().equals("net.sf.ehcache.distribution.RMICacheReplicatorFactory")) {
					replicatedCacheNames.add(eachConfig.getKey());
				}
			}
		}
		return replicatedCacheNames;
	}

	public Config getConfig() {
		return config;
	}

	public void setConfig(Config config) {
		this.config = config;
	}
}
