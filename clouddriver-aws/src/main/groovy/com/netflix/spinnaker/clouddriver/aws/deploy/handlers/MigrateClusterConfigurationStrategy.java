/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.deploy.handlers;

import com.netflix.spinnaker.clouddriver.aws.AwsConfiguration;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.LoadBalancerMigrator;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.MigrateLoadBalancerResult;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.MigrateSecurityGroupResult;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupLookupFactory.SecurityGroupLookup;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupMigrator;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupMigrator.SecurityGroupLocation;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.servergroup.ClusterConfigurationMigrator.ClusterConfigurationTarget;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.servergroup.ClusterConfigurationMigrator.ClusterConfiguration;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.servergroup.MigrateClusterConfigurationResult;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.servergroup.MigrateClusterConfigurationsAtomicOperation;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class MigrateClusterConfigurationStrategy {

  protected SecurityGroupLookup sourceLookup;
  protected SecurityGroupLookup targetLookup;
  protected MigrateSecurityGroupStrategy migrateSecurityGroupStrategy;
  protected MigrateLoadBalancerStrategy getMigrateLoadBalancerStrategy;

  abstract AmazonClientProvider getAmazonClientProvider();

  abstract RegionScopedProviderFactory getRegionScopedProviderFactory();

  abstract AwsConfiguration.DeployDefaults getDeployDefaults();

  /**
   * Migrates load balancers and security groups in a cluster configuration, returning the mutations and the cluster
   * configuration, with load balancers and security group collections updated, as well as subnetType, iamRole,
   * and keyPair
   *
   * @param source                       the source configuration
   * @param target                       the target location
   * @param sourceLookup                 a security group lookup cache for the source region
   * @param targetLookup                 a security group lookup cache for the target region (may be the same object as
   *                                     the sourceLookup)
   * @param migrateLoadBalancerStrategy  the load balancer migration strategy
   * @param migrateSecurityGroupStrategy the security group migration strategy
   * @param subnetType                   the subnetType in which to migrate the dependencies (should be null for EC
   *                                     Classic migrations)
   * @param iamRole                      the iamRole to apply when migrating (optional)
   * @param keyPair                      the keyPair to apply when migrating (optional)
   * @param dryRun                       whether to perform the migration or simply calculate the migration
   * @return a result set with the new cluster configuration and a collection of load balancers and security groups
   * required to perform the migration
   */
  public synchronized MigrateClusterConfigurationResult generateResults(ClusterConfiguration source, ClusterConfigurationTarget target,
                                                                        SecurityGroupLookup sourceLookup, SecurityGroupLookup targetLookup,
                                                                        MigrateLoadBalancerStrategy migrateLoadBalancerStrategy,
                                                                        MigrateSecurityGroupStrategy migrateSecurityGroupStrategy,
                                                                        String subnetType, String iamRole, String keyPair, boolean dryRun) {
    this.sourceLookup = sourceLookup;
    this.targetLookup = targetLookup;
    this.migrateSecurityGroupStrategy = migrateSecurityGroupStrategy;
    this.getMigrateLoadBalancerStrategy = migrateLoadBalancerStrategy;

    MigrateClusterConfigurationResult result = new MigrateClusterConfigurationResult();

    List<MigrateLoadBalancerResult> targetLoadBalancers = generateTargetLoadBalancers(source, target, subnetType, dryRun);
    List<MigrateSecurityGroupResult> targetSecurityGroups = generateTargetSecurityGroups(source, target, result, dryRun);

    result.setLoadBalancerMigrations(targetLoadBalancers);
    result.setSecurityGroupMigrations(targetSecurityGroups);

    Map<String, Object> cluster = source.getCluster();

    Map<String, List<String>> zones = new HashMap<>();
    zones.put(target.getRegion(), target.getAvailabilityZones());
    cluster.put("availabilityZones", zones);

    cluster.put("loadBalancers", targetLoadBalancers.stream().map(MigrateLoadBalancerResult::getTargetName).collect(Collectors.toList()));
    cluster.put("securityGroups", targetSecurityGroups.stream().map(s -> s.getTarget().getTargetId()).collect(Collectors.toList()));
    cluster.put("account", target.getCredentialAccount());
    if (MigrateClusterConfigurationsAtomicOperation.CLASSIC_SUBNET_KEY.equals(subnetType)) {
      cluster.remove("subnetType");
    } else {
      cluster.put("subnetType", subnetType);
    }
    if (iamRole != null) {
      cluster.put("iamRole", iamRole);
    }
    if (keyPair != null) {
      cluster.put("keyPair", keyPair);
    }
    result.setCluster(cluster);

    return result;
  }

  protected List<MigrateSecurityGroupResult> generateTargetSecurityGroups(ClusterConfiguration source,
                                                                          ClusterConfigurationTarget target,
                                                                          MigrateClusterConfigurationResult result,
                                                                          boolean dryRun) {

    source.getSecurityGroupIds().stream()
      .filter(g -> !sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId()).isPresent())
      .forEach(m -> result.getWarnings().add("Skipping creation of security group: " + m
        + " (could not be found in source location)"));

    List<String> securityGroupNames = source.getSecurityGroupIds().stream()
      .filter(g -> sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId()).isPresent())
      .map(g -> sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId()).get())
      .map(g -> g.getSecurityGroup().getGroupName())
      .collect(Collectors.toList());

    List<MigrateSecurityGroupResult> targetSecurityGroups = securityGroupNames.stream().map(group ->
      getMigrateSecurityGroupResult(source, target, sourceLookup, targetLookup, dryRun, group)
    ).collect(Collectors.toList());

    if (getDeployDefaults().getAddAppGroupToServerGroup()) {
      // if the app security group is already present, don't include it twice
      if (targetSecurityGroups.stream().noneMatch(r -> source.getApplication().equals(r.getTarget().getTargetName()))) {
        targetSecurityGroups.add(generateAppSecurityGroup(source, target, sourceLookup, targetLookup, dryRun));
      }
    }

    return targetSecurityGroups;
  }

  protected List<MigrateLoadBalancerResult> generateTargetLoadBalancers(ClusterConfiguration source,
                                                                        ClusterConfigurationTarget target,
                                                                        String subnetType,
                                                                        boolean dryRun) {
    return source.getLoadBalancerNames().stream().map(lbName ->
      getMigrateLoadBalancerResult(source, target, sourceLookup, targetLookup, subnetType, dryRun, lbName)
    ).collect(Collectors.toList());
  }

  protected MigrateSecurityGroupResult generateAppSecurityGroup(ClusterConfiguration source,
                                                                ClusterConfigurationTarget target,
                                                                SecurityGroupLookup sourceLookup,
                                                                SecurityGroupLookup targetLookup, boolean dryRun) {
    SecurityGroupMigrator.SecurityGroupLocation appGroupLocation = new SecurityGroupMigrator.SecurityGroupLocation();
    appGroupLocation.setName(source.getApplication());
    appGroupLocation.setRegion(source.getRegion());
    appGroupLocation.setCredentials(source.getCredentials());
    appGroupLocation.setVpcId(source.getVpcId());
    SecurityGroupMigrator migrator = new SecurityGroupMigrator(sourceLookup, targetLookup, migrateSecurityGroupStrategy,
      appGroupLocation, new SecurityGroupLocation(target));
    migrator.setCreateIfSourceMissing(true);
    return migrator.migrate(dryRun);
  }

  private MigrateSecurityGroupResult getMigrateSecurityGroupResult(ClusterConfiguration source,
                                                                   ClusterConfigurationTarget target,
                                                                   SecurityGroupLookup sourceLookup,
                                                                   SecurityGroupLookup targetLookup,
                                                                   boolean dryRun, String group) {
    SecurityGroupMigrator.SecurityGroupLocation sourceLocation = new SecurityGroupMigrator.SecurityGroupLocation();
    sourceLocation.setName(group);
    sourceLocation.setRegion(source.getRegion());
    sourceLocation.setCredentials(source.getCredentials());
    sourceLocation.setVpcId(source.getVpcId());
    return new SecurityGroupMigrator(sourceLookup, targetLookup, migrateSecurityGroupStrategy,
      sourceLocation, new SecurityGroupMigrator.SecurityGroupLocation(target)).migrate(dryRun);
  }

  private MigrateLoadBalancerResult getMigrateLoadBalancerResult(ClusterConfiguration source, ClusterConfigurationTarget target,
                                                                 SecurityGroupLookup sourceLookup,
                                                                 SecurityGroupLookup targetLookup, String subnetType,
                                                                 boolean dryRun, String lbName) {
    LoadBalancerMigrator.LoadBalancerLocation sourceLocation = new LoadBalancerMigrator.LoadBalancerLocation();
    sourceLocation.setName(lbName);
    sourceLocation.setRegion(source.getRegion());
    sourceLocation.setVpcId(source.getVpcId());
    sourceLocation.setCredentials(source.getCredentials());
    return new LoadBalancerMigrator(sourceLookup, targetLookup, getAmazonClientProvider(), getRegionScopedProviderFactory(),
      migrateSecurityGroupStrategy, getDeployDefaults(), getMigrateLoadBalancerStrategy, sourceLocation,
      new LoadBalancerMigrator.LoadBalancerLocation(target), subnetType, source.getApplication()).migrate(dryRun);
  }

}