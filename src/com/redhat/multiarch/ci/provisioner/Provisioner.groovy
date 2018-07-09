package com.redhat.multiarch.ci.provisioner
import com.redhat.multiarch.ci.provisioner.ConnType
import com.redhat.multiarch.ci.provisioner.HostType

import groovy.json.*
import java.util.LinkedHashMap

class Provisioner {
  def script
  ProvisioningConfig config

  Provisioner(def script, ProvisioningConfig config) {
    this.script = script
    this.config = config
  }

  /**
   * Attempts to provision a multi-arch host.
   *
   * @param arch String representing architecture of the host to provision.
   */
  Host provision(String arch) {
    Host host = new Host(
      arch: arch,
      target: 'jenkins-slave',
      name: "${arch}-slave"
    )
    if (config.hostType == HostType.CONTAINER) {
      return host
    }

    try {
      installCredentials(script)

      if (config.provisioningRepoUrl != null) {
        // Get linchpin workspace
        script.git(url: config.provisioningRepoUrl, branch: config.provisioningRepoRef)
      } else {
        script.checkout script.scm
      }

      // Attempt provisioning
      host.initialized = true
      script.sh "echo ${config.hostType}"

      // Configure ssh port 
      // Install ssh keys so that either cinch or direct ssh will connect
      if (config.hostType == HostType.BEAKER) {
        script.sh """
          . /home/jenkins/envs/provisioner/bin/activate
          linchpin --workspace ${config.provisioningWorkspaceDir} --template-data \'${getTemplateData(host)}\' --verbose up ${host.target}
      """
      }
      if (config.hostType == HostType.VM) {
        provisionKubevirtVM(config, host)
        config.connection = ConnType.SSH
      }

      // We need to scan for inventory file. Please see the following for reasoning:
      // - https://github.com/CentOS-PaaS-SIG/linchpin/issues/430
      // Possible solutions to not require the scan:
      // - https://github.com/CentOS-PaaS-SIG/linchpin/issues/421
      // - overriding [evars] section and specifying inventory_file
      //
      host.inventory = script.sh(returnStdout: true, script: """
          readlink -f ${config.provisioningWorkspaceDir}/inventories/*.inventory
          """).trim()
      script.sh "cat ${host.inventory}"
      // Now that we have the inventory file, we should populate the hostName
      // With the name of the master node
      host.hostName = script.sh(returnStdout: true, script: """
          gawk '/\\[master_node\\]/{getline; print \$1}' ${host.inventory}
          """).trim()

      host.provisioned = true

      if (config.connection == ConnType.CINCH) {
        host.connectedToMaster = true

        // We only care if the install ansible flag is set when we are running on the provisioned host
        // It's already installed on the provisioning container
        if (config.installAnsible) {
          script.node (host.name) {
            script.sh '''
              sudo yum install python-devel openssl-devel libffi-devel -y &&
              sudo mkdir -p /home/jenkins &&
              sudo chown --recursive ${USER}:${USER} /home/jenkins &&
              sudo pip install --upgrade pip &&
              sudo pip install --upgrade setuptools &&
              sudo pip install --upgrade ansible
            '''
          }
          host.ansibleInstalled = true
        }

        // We only care if the install credentials flag is set when we are running on the provisioned host
        // It's already installed on the provisioning container
        if (config.installCredentials) {
          script.node (host.name) {
            installCredentials(script)
          }
        }
        host.credentialsInstalled = true
      }

      if (config.installRhpkg) {
        script.node(host.name) {
          installRhpkg(script)
        }
      }
      host.rhpkgInstalled = true
    } catch (e) {
      script.echo "${e}"
      host.error = e.getMessage()
    }

    host
  }

  /**
   * Runs a teardown for provisioned host.
   *
   * @param host Provisioned host to be torn down.
   * @param arch String specifying the arch to run tests on.
   */
  def teardown(Host host, String arch) {
    // Prepare the cinch teardown inventory
    if (!host || !host.initialized) {
      // The provisioning job did not successfully provision a machine, so there is nothing to teardown
      script.currentBuild.result = 'SUCCESS'
      return
    }

    // Run cinch teardown if runOnSlave was attempted with a provisioned host
    if (config.connection == ConnType.CINCH && host.provisioned) {
      try {
        script.sh """
          . /home/jenkins/envs/provisioner/bin/activate
          teardown ${host.inventory}
        """
      } catch (e) {
        script.echo "${e}"
      }
    }

    if (host.initialized) {
      if (config.hostType == HostType.VM) {
        def openshift = script.openshift
        script.withCredentials([script.file(credentialsId: config.sshPubKeyCredentialId, variable: 'SSHPUBKEY')]) {
          try {
            script.ocw.exec(null, 'redhat-multiarch-qe', script) {
              openshift.raw('delete', 'vm', host.name)
            }
          } catch (e) {
            script.echo "${e}"
          }
          try {
            script.ocw.exec(null, 'redhat-multiarch-qe', script) {
              openshift.raw('delete', 'vmi', host.name)
            }
          } catch (e) {
            script.echo "${e}"
          }
          try {
            script.ocw.exec(null, 'redhat-multiarch-qe', script) {
              openshift.raw('delete', 'svc', host.name)
            }
          } catch (e) {
            script.echo "${e}"
          }
        }
      } else {
        try {
          script.sh """
            . /home/jenkins/envs/provisioner/bin/activate
            linchpin --workspace ${config.provisioningWorkspaceDir} --template-data \'${getTemplateData(host)}\' --verbose destroy ${host.target}
          """
        } catch (e) {
          script.echo "${e}"
        }
      }
    }

    if (host.error) {
      script.currentBuild.result = 'FAILURE'
    }
  }

  String getTemplateData(Host host) {
    script.withCredentials([
      script.usernamePassword(credentialsId: config.jenkinsSlaveCredentialId,
                              usernameVariable: 'JENKINS_SLAVE_USERNAME',
                              passwordVariable: 'JENKINS_SLAVE_PASSWORD')
    ]) {
      // Build template data
      def runOnSlave = true
      if (config.connection == ConnType.SSH ) {
        runOnSlave = false
      }
      def templateData = [:]
      templateData.arch = host.arch
      templateData.job_group = config.jobgroup
      templateData.hostrequires = config.hostrequires
      templateData.hooks = [postUp: [connectToMaster: runOnSlave]]
      templateData.extra_vars = "{" +
        "\"rpm_key_imports\":[]," +
        "\"jenkins_master_repositories\":[]," +
        "\"jenkins_master_download_repositories\":[]," +
        "\"jslave_name\":\"${host.name}\"," +
        "\"jslave_label\":\"${host.name}\"," +
        "\"arch\":\"${host.arch}\"," +
        "\"jenkins_master_url\":\"${config.jenkinsMasterUrl}\"," +
        "\"jenkins_slave_username\":\"${script.JENKINS_SLAVE_USERNAME}\"," +
        "\"jenkins_slave_password\":\"${script.JENKINS_SLAVE_PASSWORD}\"," +
        "\"jswarm_version\":\"3.9\"," +
        "\"jswarm_filename\":\"swarm-client-{{ jswarm_version }}.jar\"," +
        "\"jswarm_extra_args\":\"${config.jswarmExtraArgs}\"," +
        '"jenkins_slave_repositories":[{ "name": "epel", "mirrorlist": "https://mirrors.fedoraproject.org/metalink?arch=$basearch&repo=epel-7"}]' +
        "}"

      def templateDataJson = JsonOutput.toJson(templateData)
      templateDataJson
    }
  }

  void installCredentials(def script) {
    script.withCredentials([
      script.file(credentialsId: config.keytabCredentialId, variable: 'KEYTAB'),
      script.file(credentialsId: config.sshPrivKeyCredentialId, variable: 'SSHPRIVKEY'),
      script.file(credentialsId: config.sshPubKeyCredentialId, variable: 'SSHPUBKEY'),
      script.file(credentialsId: config.krbConfCredentialId, variable: 'KRBCONF'),
    ]) {
      script.env.HOME = "/home/jenkins"
      if (config.hostType == HostType.BEAKER) {
        script.withCredentials(
          [script.usernamePassword(credentialsId: config.krbPrincipalCredentialId,
                              usernameVariable: 'KRB_PRINCIPAL',
                              passwordVariable: ''),
           script.file(credentialsId: config.bkrConfCredentialId, variable: 'BKRCONF')]) {
          script.sh """
            sudo yum install -y krb5-workstation || yum install -y krb5-workstation
            sudo cp ${script.KRBCONF} /etc/krb5.conf || cp ${script.KRBCONF} /etc/krb5.conf
            sudo mkdir -p /etc/beaker || mkdir -p /etc/beaker
            sudo cp ${script.BKRCONF} /etc/beaker/client.conf || cp ${script.BKRCONF} /etc/beaker/client.conf
            sudo chmod 644 /etc/krb5.conf || chmod 644 /etc/krb5.conf
            sudo chmod 644 /etc/beaker/client.conf || chmod 644 /etc/beaker/client.conf
            kinit ${script.KRB_PRINCIPAL} -k -t ${script.KEYTAB}
         """
       }
     } 
     """
        mkdir -p ~/.ssh
        cp ${script.SSHPRIVKEY} ~/.ssh/id_rsa
        cp ${script.SSHPUBKEY} ~/.ssh/id_rsa.pub
        chmod 600 ~/.ssh/id_rsa
        chmod 644 ~/.ssh/id_rsa.pub
        eval "\$(ssh-agent -s)"
        ssh-add ~/.ssh/id_rsa
      """
    }
  }

  void installRhpkg(def script) {
    script.sh """
      echo "pkgs.devel.redhat.com,10.19.208.80 ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAplqWKs26qsoaTxvWn3DFcdbiBxqRLhFngGiMYhbudnAj4li9/VwAJqLm1M6YfjOoJrj9dlmuXhNzkSzvyoQODaRgsjCG5FaRjuN8CSM/y+glgCYsWX1HFZSnAasLDuW0ifNLPR2RBkmWx61QKq+TxFDjASBbBywtupJcCsA5ktkjLILS+1eWndPJeSUJiOtzhoN8KIigkYveHSetnxauxv1abqwQTk5PmxRgRt20kZEFSRqZOJUlcl85sZYzNC/G7mneptJtHlcNrPgImuOdus5CW+7W49Z/1xqqWI/iRjwipgEMGusPMlSzdxDX4JzIx6R53pDpAwSAQVGDz4F9eQ==" | sudo tee -a /etc/ssh/ssh_known_hosts

      echo "Host pkgs.devel.redhat.com" | sudo tee -a /etc/ssh/ssh_config
      echo "IdentityFile /home/jenkins/.ssh/id_rsa" | sudo tee -a /etc/ssh/ssh_config

      sudo yum install -y yum-utils git
      curl -L -O http://download.devel.redhat.com/rel-eng/internal/rcm-tools-rhel-7-server.repo
      sudo yum-config-manager --add-repo rcm-tools-rhel-7-server.repo
      sudo yum install -y rhpkg
      git config --global user.name "jenkins"
    """
  }

  void provisionKubevirtVM(ProvisioningConfig config, Host host) {
  script.stage('provision vm') {
    // Alias openshift plugin
    def openshift = script.openshift
      script.withCredentials([script.file(credentialsId: config.sshPubKeyCredentialId, variable: 'SSHPUBKEY'),
       script.file(credentialsId: config.sshPrivKeyCredentialId, variable: 'SSHPRIVKEY')]) {
          // Generate name for kubevirt VM
          host.name = "virt-container-" + 
            UUID.randomUUID().toString().substring(0,6)
          def public_key = script.sh(script: "cat ${script.SSHPUBKEY}",
                                    returnStdout: true)
          def template
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            template = openshift.selector('template', 'vm-template-linux')
          }
          def name = host.name
          // Process the kubevirt VM template
          def result
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            result = 
              openshift.raw("process vm-template-linux -p NAME=${name}",
                            "-p REGISTRY_IP=172.30.1.1 -p PROJECT=redhat-multiarch-qe", 
                            "-p IMAGE=fedora28 -p 'SSHPUBKEY=${public_key}'")
          }
          // Create the VM
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            openshift.create(result.out)
          }
          // Launch the VM
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            openshift.raw('patch', 'virtualmachine', name, 
              '--type merge -p \'{"spec":{"running":true}}\'')
          }

          // Get the VM description 
          def svc
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            svc = openshift.selector('svc', name).describe()
          }
          // Get the public node port
         def vm_node_port = 
           script.sh(script: "printf '${svc}' |" +
                     " gawk 'match(\$0, /NodePort/)" +
                       "{print substr(\$3,0,length(\$3)-4)}'",
                     returnStdout: true).replaceAll("\\s","")
          def oc_version
          script.ocw.exec(null, 'redhat-multiarch-qe', script) {
            oc_version = openshift.raw('version')
          }
            def cluster_ip = 
              script.sh(script: "printf '${oc_version}' |" +
                                  " gawk 'match(\$0, /Server/)" +
                                    "{print substr(\$2,9,length(substr(\$2,9)))}'",
                                returnStdout: true).tokenize(':')[0]
            // VM IP defaults to the cluster IP
            def vm_ip = cluster_ip
            def describeVm = {->
              def vm_desc
              script.ocw.exec(null, 'redhat-multiarch-qe', script) {
                vm_desc = openshift.raw('describe', 'vmi', name)
              }
              vm_desc
            }
            def getVmIp = {description->
                script.sh(script: "printf '${description}' |" +
                                    " gawk 'match(\$0, /Ip Address/)" +
                                      "{print \$3}'",
                                  returnStdout: true).replaceAll("\\s", "")
            }
            // If cluster has a localhost IP, get the VM's local IP
            // and use port 22 for ssh
            if (vm_ip.substring(0,3) == '172') {
              vm_ip = ''
              // Wait for kubevirt container to obtain an IP
              // from openshift
              while (vm_ip == '') {
                script.sh "sleep 15s"
                vm_ip = getVmIp(describeVm())
              }
              vm_node_port = '22'
            }
            // Set up the ansible inventory
            def inventory_dir = "${config.provisioningWorkspaceDir}/inventories"
            def inventory_file = "jenkins-slave.inventory"
            def inventory_hosts = [
              'rhel7',
              'certificate_authority',
              'repositories',
              'master_node',
              'jenkins_slave',
              'all']
            script.sh "mkdir -p ${inventory_dir}"
            script.sh "printf '' > ${inventory_dir}/${inventory_file}"
            inventory_hosts.each() {
              script.sh "printf '[${it}]\n' >> ${inventory_dir}/${inventory_file}"
              script.sh "printf '${vm_ip} ansible_port=${vm_node_port}\n\n'" +  
                          " >> ${inventory_dir}/${inventory_file}"
            }
            script.sh """
            cat > ~/wait_for_vm.yml << END
- name: wait for host
  hosts: localhost
  tasks:
  - name: wait for host availability
    local_action:
      module: wait_for
      port: 22
      host: "{{ hostvars['localhost']['groups']['all'] }}"
      search_regex: OpenSSH
    delay: 30
END
"""
            // Set up .ssh/config 
            script.sh """
             mkdir -p ~/.ssh && chmod 0600 ~/.ssh
             cat ${script.SSHPRIVKEY} > ~/.ssh/id_rsa
             cat ${script.SSHPUBKEY} > ~/.ssh/id_rsa.pub
             chmod 0644 ~/.ssh/id_rsa
             chmod 0644 ~/.ssh/id_rsa.pub
             printf 'Host ${vm_ip}\n' > ~/.ssh/config
             printf '    HostName ${vm_ip}\n' >> ~/.ssh/config
             printf '    Port ${vm_node_port}\n' >> ~/.ssh/config
             cat ~/.ssh/config
             ansible-playbook -i ${inventory_dir}/${inventory_file} ~/wait_for_vm.yml
             ssh -o StrictHostKeyChecking=no -i ${script.SSHPRIVKEY} root@${vm_ip} 'yum install -y python libselinux-python'
           """
        }
    }
  }
}
