import com.redhat.multiarch.ci.provisioner.*

def call(ProvisioningConfig config, Host host) {
  // Cinch or Container Mode
  if (config.connection == ConnType.CONTAINER) {
    sh """. /home/jenkins/envs/provisioner/bin/activate
          ansible-playbook -i 'localhost,' -c local ${params.TEST_DIR}/ansible-playbooks/*/playbook.yml"""
    sh """. /home/jenkins/envs/provisioner/bin/activate 
          for i in ${params.TEST_DIR}/scripts/*/test.sh; do bash \$i; done"""
    return
  }
  else if (config.connection == ConnType.CINCH) {
    sh "ansible-playbook -i 'localhost,' -c local ${params.TEST_DIR}/ansible-playbooks/*/playbook.yml"
    sh "for i in ${params.TEST_DIR}/scripts/*/test.sh; do bash \$i; done"
    return
  }

  // SSH Mode
  sh ". /home/jenkins/envs/provisioner/bin/activate; ansible-playbook -i '${host.inventory}' ${params.TEST_DIR}/ansible-playbooks/*/playbook.yml"
  sh "for i in ${params.TEST_DIR}/scripts/*/test.sh; do ssh -i ~/.ssh/id_rsa root@${host.hostName} < \$i; done"
}
