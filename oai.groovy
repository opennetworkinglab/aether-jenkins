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

[oai_nodes]
node1
EOF
            sudo cp vars/main-oai.yml vars/main.yml
            sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
            sudo sed -i "s/ens18/\$MYIFC/g" vars/main.yml
            make aether-pingall
          """ 
        }
    }
    
    stage('Install Aether') {
        steps {
          sh """
            cd $WORKSPACE/aether-onramp
            make k8s-install
            make 5gc-install
            make oai-gnb-install
            kubectl get pods -n aether-5gc
          """ 
        }
    }

    stage('Run Emulated UE'){
        steps {
            retry(2) {
                 sh """
                   cd $WORKSPACE/aether-onramp
                   sleep 60
                   make oai-uesim-start
                   docker ps
                 """
            } 
        }
    }

    stage ('Validate Results'){
        steps {
            catchError(message:'UEsim Validation fails', buildResult:'FAILURE', stageResult:'FAILURE')
            {
                sh """
                  cd $WORKSPACE
                  docker exec rfsim5g-oai-nr-ue ping -c 2 -I oaitun_ue1 192.168.250.1 > UEsim.log
                  grep "0% packet loss" UEsim.log
                """
            }    
        }
    }
	
    stage ('Retrieve Logs'){
        steps {
            sh '''
              cd $WORKSPACE
              mkdir logs
              cp UEsim.log logs
              cd logs
              AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n aether-5gc > oai_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n aether-5gc > oai_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n aether-5gc > oai_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n aether-5gc > oai_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n aether-5gc > oai_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n aether-5gc > oai_smf.log
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
        cd $WORKSPACE/aether-onramp
        make oai-uesim-stop
        make oai-gnb-uninstall
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
