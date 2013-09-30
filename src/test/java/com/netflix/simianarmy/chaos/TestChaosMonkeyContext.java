// CHECKSTYLE IGNORE Javadoc
/*
 *
 *  Copyright 2012 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.simianarmy.chaos;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.ExecChannel;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.io.Payload;
import org.jclouds.ssh.SshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.netflix.simianarmy.CloudClient;
import com.netflix.simianarmy.MonkeyConfiguration;
import com.netflix.simianarmy.TestMonkeyContext;
import com.netflix.simianarmy.basic.BasicConfiguration;
import com.netflix.simianarmy.basic.chaos.BasicChaosInstanceSelector;
import com.netflix.simianarmy.chaos.ChaosCrawler.InstanceGroup;

public class TestChaosMonkeyContext extends TestMonkeyContext implements ChaosMonkey.Context {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestChaosMonkeyContext.class);
    private final BasicConfiguration cfg;

    public TestChaosMonkeyContext() {
        this(new Properties());
    }

    protected TestChaosMonkeyContext(Properties properties) {
        super(ChaosMonkey.Type.CHAOS);
        cfg = new BasicConfiguration(properties);
    }

    public TestChaosMonkeyContext(String propFile) {
        super(ChaosMonkey.Type.CHAOS);
        Properties props = new Properties();
        try {
            InputStream is = TestChaosMonkeyContext.class.getResourceAsStream(propFile);
            try {
                props.load(is);
            } finally {
                is.close();
            }
        } catch (Exception e) {
            LOGGER.error("Unable to load properties file " + propFile, e);
        }
        cfg = new BasicConfiguration(props);
    }

    @Override
    public MonkeyConfiguration configuration() {
        return cfg;
    }

    public static class TestInstanceGroup implements InstanceGroup {
        private final Enum type;
        private final String name;
        private final String region;
        private final List<String> instances = new ArrayList<String>();

        public TestInstanceGroup(Enum type, String name, String region, String... instances) {
            this.type = type;
            this.name = name;
            this.region = region;
            for (String i : instances) {
                this.instances.add(i);
            }
        }

        @Override
        public Enum type() {
            return type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String region() {
            return region;
        }

        @Override
        public List<String> instances() {
            return Collections.unmodifiableList(instances);
        }

        @Override
        public void addInstance(String ignored) {
        }

        public void deleteInstance(String id) {
            instances.remove(id);
        }

        @Override
        public InstanceGroup copyAs(String newName) {
            return new TestInstanceGroup(this.type, newName, this.region, instances().toString());
        }
    }

    public enum CrawlerTypes {
        TYPE_A, TYPE_B, TYPE_C, TYPE_D
    };

    @Override
    public ChaosCrawler chaosCrawler() {
        return new ChaosCrawler() {
            @Override
            public EnumSet<?> groupTypes() {
                return EnumSet.allOf(CrawlerTypes.class);
            }

            @Override
            public List<InstanceGroup> groups() {
                InstanceGroup gA0 = new TestInstanceGroup(CrawlerTypes.TYPE_A, "name0", "reg1", "0:i-123456780");
                InstanceGroup gA1 = new TestInstanceGroup(CrawlerTypes.TYPE_A, "name1", "reg1", "1:i-123456781");
                InstanceGroup gB2 = new TestInstanceGroup(CrawlerTypes.TYPE_B, "name2", "reg1", "2:i-123456782");
                InstanceGroup gB3 = new TestInstanceGroup(CrawlerTypes.TYPE_B, "name3", "reg1", "3:i-123456783");
                InstanceGroup gC1 = new TestInstanceGroup(CrawlerTypes.TYPE_C, "name4", "reg1", "3:i-123456784",
                        "3:i-123456785");
                InstanceGroup gC2 = new TestInstanceGroup(CrawlerTypes.TYPE_C, "name5", "reg1", "3:i-123456786",
                        "3:i-123456787");
                InstanceGroup gD0 = new TestInstanceGroup(CrawlerTypes.TYPE_D, "new-group-TestGroup1-XXXXXXXXX",
                        "reg1", "3:i-123456786", "3:i-123456787");
                return Arrays.asList(gA0, gA1, gB2, gB3, gC1, gC2, gD0);
            }

            @Override
            public List<InstanceGroup> groups(String... names) {
                Map<String, InstanceGroup> nameToGroup = new HashMap<String, InstanceGroup>();
                for (InstanceGroup ig : groups()) {
                    nameToGroup.put(ig.name(), ig);
                }
                List<InstanceGroup> list = new LinkedList<InstanceGroup>();
                for (String name : names) {
                    InstanceGroup ig = nameToGroup.get(name);
                    if (ig == null) {
                        continue;
                    }
                    for (String instanceId : selected) {
                        // Remove selected instances from crawler list
                        TestInstanceGroup testIg = (TestInstanceGroup) ig;
                        testIg.deleteInstance(instanceId);
                    }
                    list.add(ig);
                }
                return list;
            }
        };
    }

    private final List<InstanceGroup> selectedOn = new LinkedList<InstanceGroup>();

    public List<InstanceGroup> selectedOn() {
        return selectedOn;
    }

    @Override
    public ChaosInstanceSelector chaosInstanceSelector() {
        return new BasicChaosInstanceSelector() {
            @Override
            public Collection<String> select(InstanceGroup group, double probability) {
                selectedOn.add(group);
                Collection<String> instances = super.select(group, probability);
                selected.addAll(instances);
                return instances;
            }
        };
    }

    private final List<String> terminated = new LinkedList<String>();
    private final List<String> selected = new LinkedList<String>();

    public List<String> terminated() {
        return terminated;
    }

    @Override
    public CloudClient cloudClient() {
        return new CloudClient() {
            @Override
            public void terminateInstance(String instanceId) {
                terminated.add(instanceId);
            }

            @Override
            public void createTagsForResources(Map<String, String> keyValueMap, String... resourceIds) {
            }

            @Override
            public void deleteAutoScalingGroup(String asgName) {
            }

            @Override
            public void deleteVolume(String volumeId) {
            }

            @Override
            public void deleteSnapshot(String snapshotId) {
            }

            @Override
            public void deleteImage(String imageId) {
            }

            @Override
            public void deleteLaunchConfiguration(String launchConfigName) {
            }

            @Override
            public List<String> listAttachedVolumes(String instanceId, boolean includeRoot) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void detachVolume(String instanceId, String volumeId, boolean force) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ComputeService getJcloudsComputeService() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getJcloudsId(String instanceId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SshClient connectSsh(String instanceId, LoginCredentials credentials) {
                return new MockSshClient(instanceId, credentials);
            }
        };
    }

    final List<SshAction> sshActions = Lists.newArrayList();

    public static class SshAction {
        public String instanceId;
        public String method;
        public String path;
        public String contents;
        public String command;
    }

    private class MockSshClient implements SshClient {
        final String instanceId;
        final LoginCredentials credentials;

        public MockSshClient(String instanceId, LoginCredentials credentials) {
            this.instanceId = instanceId;
            this.credentials = credentials;
        }

        @Override
        public String getUsername() {
            return credentials.getUser();
        }

        @Override
        public String getHostAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(String path, Payload contents) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Payload get(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecResponse exec(String command) {
            SshAction action = new SshAction();
            action.method = "exec";
            action.instanceId = instanceId;
            action.command = command;
            sshActions.add(action);

            String output = "";
            String error = "";
            int exitStatus = 0;
            return new ExecResponse(output, error, exitStatus);
        }

        @Override
        public ExecChannel execChannel(String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public void put(String path, String contents) {
            SshAction action = new SshAction();
            action.method = "put";
            action.instanceId = instanceId;
            action.path = path;
            action.contents = contents;
            sshActions.add(action);
        }
    }

    private List<Notification> groupNotified = Lists.newArrayList();
    private List<Notification> globallyNotified = Lists.newArrayList();

    static class Notification {
        public String instance;
        public ChaosType chaosType;

        public Notification(String instance, ChaosType chaosType) {
            this.instance = instance;
            this.chaosType = chaosType;
        }
    }

    @Override
    public ChaosEmailNotifier chaosEmailNotifier() {
        return new ChaosEmailNotifier(null) {
            @Override
            public String getSourceAddress(String to) {
                return "source@chaosMonkey.foo";
            }

            @Override
            public String[] getCcAddresses(String to) {
                return new String[] {};
            }

            @Override
            public String buildEmailSubject(String to) {
                return String.format("Testing Chaos termination notification for %s", to);
            }

            @Override
            public void sendTerminationNotification(InstanceGroup group, String instance, ChaosType chaosType) {
                groupNotified.add(new Notification(instance, chaosType));
            }

            @Override
            public void sendTerminationGlobalNotification(InstanceGroup group, String instance, ChaosType chaosType) {
                globallyNotified.add(new Notification(instance, chaosType));
            }
        };
    }

    public int getNotified() {
        return groupNotified.size();
    }

    public int getGloballyNotified() {
        return globallyNotified.size();
    }

    public List<Notification> getNotifiedList() {
        return groupNotified;
    }

    public List<Notification> getGloballyNotifiedList() {
        return globallyNotified;
    }

    public List<SshAction> getSshActions() {
        return sshActions;
    }
}
