package com.redhat.multiarch.ci.provisioner

class ProvisioningConfig {
  // Provisioner version
  String version = 'v1.0'
  // Jenkins kubernetes cloud name
  String cloudName = 'openshift'
  // Job group for Beaker provisioning.
  String jobgroup = 'multiarch-qe'
  // Host requirements to pass to linchpin
  List<HashMap> hostrequires = []
  // name of Openshift tenant to pull provisioning image from.
  String tenant = 'redhat-multiarch-qe'
  // docker url to pull provisioning image from.
  String dockerUrl = '172.30.1.1:5000'
  // name of provisioning image. Can include tag name.
  String provisioningImage = 'provisioner'
  // Provisioning repo url
  String provisioningRepoUrl = null
  // Provisioning repo ref
  String provisioningRepoRef = null
  // Provisioning workspace location (needed for Linchpin)
  // This can reference a relative path in the above repo
  // or it can reference a relative path that already exists
  // in the current directory
  String provisioningWorkspaceDir = 'workspace'
  // ID of Jenkins credential for kerberos principal needed for Beaker authentication.
  String krbPrincipalCredentialId = 'redhat-multiarch-qe-krbprincipal'
  // ID of Jenkins credential for keytab needed for Beaker authentication.
  String keytabCredentialId = 'redhat-multiarch-qe-keytab'
  // ID of the Jenkins credential for the krb conf needed for Beaker authentication.
  String krbConfCredentialId = 'redhat-multiarch-qe-krbconf'
  // ID of the Jenkins credential for the bkr conf needed for Beaker authentication.
  String bkrConfCredentialId = 'redhat-multiarch-qe-bkrconf'
  // ID of Jenkins credential for SSH private key to will be
  // copied to provisioned resource.
  // *** This must be the same as what was added to Beaker ***
  String sshPrivKeyCredentialId = 'redhat-multiarch-qe-sshprivkey'
  // ID of Jenkins credential for SSH public key to will be
  // copied to provisioned resource
  // *** This must be the same as what was added to Beaker ***
  String sshPubKeyCredentialId = 'redhat-multiarch-qe-sshpubkey'
  // ID of the Jenkins credential for the username and password
  // used by cinch to connect the provisioned host to the Jenkins master
  String jenkinsSlaveCredentialId = 'jenkins-slave-credentials'
  // URL of the Jenkins master that cinch will use to connect the provisioned
  // host as a slave.
  String jenkinsMasterUrl = ""
  // Extra arguments passed to the jswarm call.
  // Allows for the connection to be tunneled in the case of an OpenShift hosted Jenkins.
  String jswarmExtraArgs = ""
  // Type of connection to the testing host
  ConnType connection = ConnType.CINCH
  // Whether Ansible should be installed on the provisioned host.
  // This will only be respected if runOnSlave is also set to true,
  // since jobs that are run via ssh already have access to ansible in the
  // provisioning container.
  Boolean installAnsible = true
  // Whether the ssh keypair and kerberos keytab should be installed on the provisioned host
  // These are already installed on the provisioning container
  Boolean installCredentials = true
  // Whether rhpkg should be installed on the provisioned host
  // This is only needed for tests that will use it to install from pkgs.devel.redhat.com
  Boolean installRhpkg = false

  HostType hostType = HostType.BEAKER

  Integer sshPort = 22

  String KubevirtInstanceName = "kubevirt-vm"
  String KubevirtInstanceMem = "4096Mi"
  String KubevirtInstanceCores = "4"
  String KubevirtInstanceRegIP = "172.30.1.1"
  String KubevirtInstanceProj = "redhat-multiarch-qe"
  String KubevirtInstanceImage = "fedora28"

  ProvisioningConfig(params, env) {
    this.krbPrincipalCredentialId = params.KRBPRINCPALCREDENTIALID ?: this.krbPrincipalCredentialId
    this.keytabCredentialId = params.KEYTABCREDENTIALID ?: this.keytabCredentialId
    this.sshPrivKeyCredentialId = params.SSHPRIVKEYCREDENTIALID ?: this.sshPrivKeyCredentialId
    this.sshPubKeyCredentialId = params.SSHPUBKEYCREDENTIALID ?: this.sshPubKeyCredentialId
    this.jenkinsSlaveCredentialId = params.JENKINSSLAVECREDENTIALID ?: this.jenkinsSlaveCredentialId
    this.jenkinsMasterUrl = env.JENKINS_MASTER_URL ?: this.jenkinsMasterUrl
    this.jswarmExtraArgs = env.JSWARM_EXTRA_ARGS ?: this.jswarmExtraArgs
  }
}
