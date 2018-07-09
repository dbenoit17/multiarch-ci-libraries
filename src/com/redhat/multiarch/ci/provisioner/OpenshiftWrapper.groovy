package com.redhat.multiarch.ci.provisioner
import groovy.transform.Synchronized

// Must be used as Singleton
// Singleton macro is not working with pipeline 
class OpenshiftWrapper {

  Boolean locked = false;

  private acquire() {
    while (this.locked) {
      sleep(3)
    }
    this.locked = true
  }

  private release() {
    this.locked = false

  }
  public <V> V exec(def cluster, def project, def script, Closure body) {
    script.openshift.withCluster(cluster) {
      script.openshift.withProject(project) {
        this.acquire()
        try {
          body()
        } finally {
         this.release()
        }
      }
    }
  }
}
