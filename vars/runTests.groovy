import com.redhat.multiarch.ci.provisioner.*

def call(ProvisioningConfig config, Host host) {
  // Cinch Mode
  if (config.connection == ConnType.CINCH || config.hostType == HostType.CONTAINER) {
    sh "ansible-playbook -i 'localhost,' -c local ${params.TEST_DIR}/ansible-playbooks/*/playbook.yml"
    sh "for i in ${params.TEST_DIR}/scripts/*/test.sh; do bash \$i; done"

    return
  }

  // SSH Mode
  sh ". /home/jenkins/envs/provisioner/bin/activate; ansible-playbook -i '${host.inventory}' ${params.TEST_DIR}/ansible-playbooks/*/playbook.yml"
  sh "for i in ${params.TEST_DIR}/scripts/*/test.sh; do ssh -i ~/.ssh/id_rsa root@${host.hostName} < \$i; done"
}
