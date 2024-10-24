// SPDX-FileCopyrightText: 2021 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0

pipeline {
    options {
      timeout(time: 1, unit: 'HOURS') 
    }

  agent {
        label 'Mumbai-Master-A2-22.04-with-AWS-setup'
    }
    
  stages{
      
    stage('Verify AWS Accessible') {
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ',
              secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              aws --region us-west-2 ec2 start-instances --instance-ids    i-000f1f7e33fe5a86e
              aws --region us-west-2 ec2 modify-instance-attribute --no-source-dest-check \
                  --instance-id i-000f1f7e33fe5a86e
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e
              sleep 300
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e \
                  --query 'Reservations[0].Instances[0].PrivateIpAddress'
            """
          }
        }
    }
    
    stage('Configure OnRamp') {
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                           --instance-ids i-000f1f7e33fe5a86e \
                           --query 'Reservations[0].Instances[0].PrivateIpAddress')
              echo \$NEWIP
              WORKERIP=\$(echo \$NEWIP | tr -d '"')
              echo \$WORKERIP
              cd $WORKSPACE
              git clone --recursive https://github.com/opennetworkinglab/aether-onramp.git 
              cd aether-onramp
              # Determine Local IP
              MYIP=\$(hostname -I | awk '{print \$1}')
              echo "MY IP is: " \$MYIP
              which curl
              which wget
              MYID=\$(wget -q -O - http://169.254.169.254/latest/meta-data/instance-id)
              echo "MYID is " \$MYID
              aws --region us-west-2 ec2 modify-instance-attribute --no-source-dest-check \
                  --instance-id \$MYID
              echo "WORKER SourceDestCheck"
              aws --region us-west-2 ec2 describe-instances --instance-ids i-000f1f7e33fe5a86e \
                  --query 'Reservations[0].Instances[0].NetworkInterfaces[0].SourceDestCheck'
              echo "MAIN SourceDestCheck"
              aws --region us-west-2 ec2 describe-instances --instance-ids \$MYID \
                  --query 'Reservations[0].Instances[0].NetworkInterfaces[0].SourceDestCheck'
              # Determine active network interface (8.8.8.8 is Google)
              MYIFC=\$(ip route get 8.8.8.8| awk '{print \$5}'|awk /./)
              echo "MY IFC is: " \$MYIFC
              # Create appropriate hosts.ini file
              cat > hosts.ini << EOF
[all]
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=/home/ubuntu/aether-qa.pem ansible_sudo_pass=ubuntu
node2 ansible_host=\$WORKERIP ansible_user=ubuntu ansible_ssh_private_key_file=/home/ubuntu/aether-qa.pem ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[gnbsim_nodes]
node2
EOF
              #sudo cp -r /home/ubuntu/host.ini hosts.ini
              cat hosts.ini
              sudo cp  vars/main-gnbsim.yml  vars/main.yml
              grep -rl "ens18" . | xargs sed -i "s/ens18/\$MYIFC/g"
              sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
              make aether-pingall
              sleep 240
            """ 
          }
        }
    }

    stage('Install Aether') {
        steps {
          sh """
            cd $WORKSPACE/aether-onramp
            make aether-k8s-install
            make aether-5gc-install
            make aether-gnbsim-install
            kubectl get pods -n omec
          """ 
        }
    }
    
    stage("Run gNBsim"){
        steps {
          withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
            credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
              NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                       --instance-ids i-000f1f7e33fe5a86e \
                       --query 'Reservations[0].Instances[0].PrivateIpAddress')
              echo \$NEWIP
              WORKERIP=\$(echo \$NEWIP | tr -d '"')
              echo \$WORKERIP
              cd $WORKSPACE/aether-onramp
              sleep 120
              make aether-gnbsim-run
              cd /home/ubuntu
              ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                 "docker exec  gnbsim-1 cat summary.log"
              ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                 "ip route get 8.8.8.8"
              ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                 "docker exec  gnbsim-1 ip route get 8.8.8.8"
            """
          }
        }
    }
    
    stage("Validate Results"){
        steps {
          catchError(message:'Gnbsim Validation is failed', buildResult:'FAILURE',
            stageResult:'FAILURE') {
            withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
              sh """
                NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                             --instance-ids i-000f1f7e33fe5a86e \
                             --query 'Reservations[0].Instances[0].PrivateIpAddress')
                echo \$NEWIP
                WORKERIP=\$(echo \$NEWIP | tr -d '"')
                echo \$WORKERIP
                cd /home/ubuntu
                LOGFILE=\$(ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                    "docker exec  gnbsim-1 ls " | grep "gnbsim1-.*.log")  || true
                echo \$LOGFILE 
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                    "mkdir -p /tmp/logs/gnbsim"
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                    "docker cp gnbsim-1:/gnbsim/bin/\$LOGFILE  /tmp/logs/gnbsim/\$LOGFILE"
                echo "Contents of gnbsim LOG:"
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                    "cat /tmp/logs/gnbsim/\$LOGFILE"
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                    "docker exec gnbsim-1 cat \$LOGFILE" | grep "Profile Status: PASS"
              """
            }
          }
        }
    }
    
    stage("Retrieve Logs"){
        steps {
          catchError(message:'Collect GNBSIM-LOGS Validation failed', buildResult:'FAILURE', 
            stageResult:'FAILURE') {
            withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
              credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
              sh """
                NEWIP=\$(aws --region us-west-2 ec2 describe-instances \
                             --instance-ids i-000f1f7e33fe5a86e \
                             --query 'Reservations[0].Instances[0].PrivateIpAddress')
                echo \$NEWIP
                WORKERIP=\$(echo \$NEWIP | tr -d '"')
                echo \$WORKERIP
                cd /home/ubuntu
                kubectl get pods -n omec
                LOGFILE=\$(ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                       "docker exec  gnbsim-1 ls " | grep "gnbsim1-.*.log")  || true
                echo \$LOGFILE 
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                       "mkdir -p /tmp/logs/gnbsim"
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                       "docker cp gnbsim-1:/gnbsim/bin/\$LOGFILE  /tmp/logs/gnbsim/\$LOGFILE"
                ssh -i "aether-qa.pem" -o StrictHostKeyChecking=no ubuntu@\$WORKERIP \
                       "cat /tmp/logs/gnbsim/\$LOGFILE"
                cd  $WORKSPACE/
                mkdir logs
                AMF_POD_NAME=\$(kubectl get pods -n omec | grep amf | awk 'NR==1{print \$1}') 
                echo \$AMF_POD_NAME
                kubectl logs \$AMF_POD_NAME -n omec > logs/2server_2204_default_amf.log
                WEBUI_POD_NAME=\$(kubectl get pods -n omec | grep webui | awk 'NR==1{print \$1}') 
                echo \$WEBUI_POD_NAME
                kubectl logs \$WEBUI_POD_NAME -n omec > logs/2server_2204_default_webui.log
                UDR_POD_NAME=\$(kubectl get pods -n omec | grep udr | awk 'NR==1{print \$1}') 
                echo \$UDR_POD_NAME
                kubectl logs \$UDR_POD_NAME -n omec > logs/2server_2204_default_udr.log
                UDM_POD_NAME=\$(kubectl get pods -n omec | grep udm | awk 'NR==1{print \$1}') 
                echo \$UDM_POD_NAME
                kubectl logs \$UDM_POD_NAME -n omec > logs/2server_2204_default_udm.log
                AUSF_POD_NAME=\$(kubectl get pods -n omec | grep ausf | awk 'NR==1{print \$1}') 
                echo \$AUSF_POD_NAME
                kubectl logs \$AUSF_POD_NAME -n omec > logs/2server_2204_default_ausf.log
                SMF_POD_NAME=\$(kubectl get pods -n omec | grep smf | awk 'NR==1{print \$1}') 
                echo \$SMF_POD_NAME
                kubectl logs \$SMF_POD_NAME -n omec > logs/2server_2204_default_smf.log
              """
            }
          }
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
      withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        credentialsId: 'AKIA6OOX34YQ5DJLY5GJ', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
          cd $WORKSPACE/aether-onramp
          make gnbsim-uninstall
          make 5gc-uninstall
          make k8s-uninstall
          aws --region us-west-2 ec2 stop-instances --instance-ids    i-000f1f7e33fe5a86e
        """
      }
    }
    
    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
            
    }
  }
}