package com.redhat.multiarch.ci.test

import com.redhat.multiarch.ci.provisioner.Host
import com.redhat.multiarch.ci.provisioner.Provisioner
import com.redhat.multiarch.ci.provisioner.ProvisioningConfig
import com.redhat.multiarch.ci.provisioner.ConnType

class Test {
  def script
  String arch
  ProvisioningConfig config
  Closure test
  Closure onTestFailure
  Closure postTest

  /**
   * @param script WorkflowScript that the test will run in.
   * @param arch String specifying the arch to run tests on.
   * @param config ProvisioningConfig Configuration for provisioning.
   * @param test Closure that takes the Host used by the test.
   * @param onTestFailure Closure that take the Host used by the test and the Exception that occured.
   */
  Test(def script, String arch, ProvisioningConfig config, Closure test, Closure onTestFailure, Closure postTest) {
    this.script = script
    this.arch = arch
    this.config = config
    this.test = test
    this.onTestFailure = onTestFailure
    this.postTest = postTest
  }

  /*
   * Runs @test on a multi-arch provisioned host for the specified arch.
   * Runs @onTestFailure if it encounters an Exception.
   */
  def run() {
    script.node("provisioner-${config.version}") {
      Provisioner provisioner = new Provisioner(script, config)
      Host host
      try {
        script.stage('Provision Host') {
          host = provisioner.provision(arch)

          // Property validity check
          if (!host.name || !host.arch) {
            script.error "Invalid provisioned host: ${host}"
          }

          // If the provision failed, there will be an error
          if (host.error) {
            script.error host.error
          }
        }
      } catch (e) {
        onTestFailure(e, host)
        teardown(provisioner, host)
        return
      }

      if (config.connection == ConnType.CINCH) {
        script.node(host.name) {
          try {
            test(host, config)
          } catch (e) {
            onTestFailure(e, host)
          } finally {
            postTest()
          }
        }

        teardown(provisioner, host)
        return
      }

      try {
        test(host, config)
      } catch (e) {
        onTestFailure(e, host)
      } finally {
        postTest()
        teardown(provisioner, host)
      }
    }
  }

  void teardown(Provisioner provisioner, Host host) {
    try {
      // Ensure teardown runs before the pipeline exits
      script.stage ('Teardown Host') {
        provisioner.teardown(host, arch)
      }
    } catch (e) {
    }
  }
}
