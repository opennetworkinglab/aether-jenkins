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

[gnbsim_nodes]
node1
EOF
            sudo cp vars/main-quickstart.yml vars/main.yml
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
            make aether-k8s-install
            make aether-5gc-install
            make aether-gnbsim-install
          """ 
        }
    }

    stage('Run gNBsim'){
        steps {
            retry(2) {
                 sh """
                   cd $WORKSPACE/aether-onramp
                   sleep 60
                   kubectl get pods -n omec 
                   docker ps
                   make aether-gnbsim-run
                   docker exec gnbsim-1 cat summary.log
                 """
            } 
        }
    }

    stage ('Validate Results'){
        steps {
            catchError(message:'gNBsim Validation fails', buildResult:'FAILURE', stageResult:'FAILURE')
            {
                sh """
                  docker exec gnbsim-1 cat summary.log  | grep "Profile Status: PASS"
                """
            }    
        }
    }
	
    stage ('Retrieve Logs'){
        steps {
            sh '''
              cd  $WORKSPACE/
              mkdir logs
              kubectl get pods -n omec
              logfile=\$(docker exec gnbsim-1 ls | grep "gnbsim1-.*.log")
              echo "${logfile}"
              docker cp gnbsim-1:/gnbsim/bin/${logfile} logs/${logfile}
              #cat logs/${logfile}
              AMF_POD_NAME=\$(kubectl get pods -n omec | grep amf | awk 'NR==1{print \$1}') 
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n omec > logs/quickstart_2204_default_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n omec | grep webui | awk 'NR==1{print \$1}') 
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n omec > logs/quickstart_2204_default_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n omec | grep udr | awk 'NR==1{print \$1}') 
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n omec > logs/quickstart_2204_default_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n omec | grep udm | awk 'NR==1{print \$1}') 
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n omec > logs/quickstart_2204_default_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n omec | grep ausf | awk 'NR==1{print \$1}') 
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n omec > logs/quickstart_2204_default_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n omec | grep smf | awk 'NR==1{print \$1}') 
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n omec > logs/quickstart_2204_default_smf.log
            '''
        }
    }

    stage("Archive Artifacts"){
        steps {
            // Archive Pod Logs
            archiveArtifacts allowEmptyArchive: true, artifacts: "**/logs/*.log", followSymlinks: false
        }
    }

  }

  post {
    always {
      sh """
        cd $WORKSPACE/aether-onramp
        make gnbsim-uninstall
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
