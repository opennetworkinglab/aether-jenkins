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
            sudo cp vars/main-upf.yml vars/main.yml
            sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
            sudo sed -i "s/ens18/\$MYIFC/g" vars/main.yml
            sudo sed -i "s/standalone: true/standalone: false/" vars/main.yml
            make aether-pingall
          """ 
        }
    }
    
    stage('Install Aether') {
        steps {
          sh """
            cd $WORKSPACE/aether-onramp
            make k8s-install
            make roc-install
            make roc-load
            make 5gc-install
            sudo sed -i "s/roc-5g-models.json/roc-5g-models-upf2.json/" vars/main.yml
            make 5gc-upf-install
            make roc-load
            make gnbsim-install
            kubectl get pods --all-namespaces
            docker ps
          """ 
        }
    }

    stage('Run gNBsim'){
        steps {
            retry(2) {
                 sh """
                   cd $WORKSPACE/aether-onramp
                   sleep 60
                   make aether-gnbsim-run
                   docker exec gnbsim-1 cat summary.log
                   docker exec gnbsim-2 cat summary.log
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
                  docker exec gnbsim-2 cat summary.log  | grep "Profile Status: PASS"
                """
            }    
        }
    }
	
    stage ('Retrieve Logs'){
        steps {
            sh '''
              mkdir $WORKSPACE/logs
              cd $WORKSPACE/logs
              logfile=\$(docker exec gnbsim-1 ls | grep "gnbsim1-.*.log")
              echo "${logfile}"
              docker cp gnbsim-1:/gnbsim/bin/${logfile} ${logfile}
              logfile=\$(docker exec gnbsim-2 ls | grep "gnbsim2-.*.log")
              echo "${logfile}"
              docker cp gnbsim-2:/gnbsim/bin/${logfile} ${logfile}
              AMF_POD_NAME=\$(kubectl get pods -n omec | grep amf | awk 'NR==1{print \$1}') 
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n omec > upf_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n omec | grep webui | awk 'NR==1{print \$1}') 
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n omec > upf_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n omec | grep udr | awk 'NR==1{print \$1}') 
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n omec > upf_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n omec | grep udm | awk 'NR==1{print \$1}') 
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n omec > upf_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n omec | grep ausf | awk 'NR==1{print \$1}') 
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n omec > upf_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n omec | grep smf | awk 'NR==1{print \$1}') 
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n omec > upf_smf.log
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
        make gnbsim-uninstall
        make 5gc-uninstall
        make roc-uninstall
        make k8s-uninstall
      """
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
            
    }
  }
}
