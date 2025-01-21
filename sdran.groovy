// SPDX-FileCopyrightText: 2023 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0

pipeline {
  options {
    timeout(time: 1, unit: 'HOURS') 
  }

  agent {
    label "${AgentLabel}"
  }
    
  stages{

    stage('Configure OnRamp') {
        steps {
          sh """
            set -e
            cd $WORKSPACE
            git clone --recursive https://github.com/opennetworkinglab/aether-onramp.git 
            cd aether-onramp
            MYIP=\$(hostname -I | awk '{print \$1}')
            echo "MY IP is: " \$MYIP
            MYIFC=\$(ip route get 8.8.8.8| awk '{print \$5}'|awk /./)
            echo "MY IFC is: " \$MYIFC
            cat > hosts.ini << EOF
            [all]
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=/home/ubuntu/aether-qa.pem ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[gnbsim_nodes]
node1
EOF
            sudo cp vars/main-sdran.yml vars/main.yml
            sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
            sudo sed -i "s/ens18/\$MYIFC/g" vars/main.yml
            make aether-pingall
          """ 
        }
    }
    
    stage('Install Aether') {
        steps {
          sh """
            set -e
            cd $WORKSPACE/aether-onramp
            make k8s-install
            make 5gc-install
            make sdran-install
            kubectl get pods -n aether-5gc
            kubectl get pods -n sdran
          """ 
        }
    }

    stage ('Validate Results'){
        steps {
            catchError(message: 'RANSIM Validation fails', buildResult: 'FAILURE', stageResult: 'FAILURE') { errorMessage ->
                echo "Error during validation: ${errorMessage}"
                script {
                    try {
                        echo "Give the simulator a chance to run (ideally, playbook should loop until done)"
                        sleep 60
                        sh """
                            kubectl exec -i deployment/onos-cli -n sdran -- onos kpimon list metrics --no-headers > ransim.log
                            kubectl exec -i deployment/onos-cli -n sdran -- onos ransim get ueCount >> ransim.log
                            kubectl exec -i deployment/onos-cli -n sdran -- onos ransim get cells --no-headers >> ransim.log
                            kubectl exec -i deployment/onos-cli -n sdran -- onos topo get entity e2cell >> ransim.log
                        """
                        echo "RANSIM log output:"
                        sh "cat ransim.log"
                    } catch (e) {
                        echo "Error during RANSIM execution: ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        throw e
                    }
                  }
              }
          }
      }

    stage ('Retrieve Logs'){
        steps {
            sh '''
              set -e
              cd $WORKSPACE
              mkdir logs
              cp ransim.log logs
              cd logs
              AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n aether-5gc > sdran_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n aether-5gc > sdran_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n aether-5gc > sdran_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n aether-5gc > sdran_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n aether-5gc > sdran_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n aether-5gc > sdran_smf.log
            '''
        }
    }

    stage("Archive Artifacts"){
        steps {
            archiveArtifacts allowEmptyArchive: true, artifacts: "**/logs/*.log", followSymlinks: false
        }
    }

  }

  post {
    always {
      sh """
        set -e
        cd $WORKSPACE/aether-onramp
        make sdran-uninstall
        make 5gc-uninstall
        make k8s-uninstall
      """
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
            
    }
  }
}
