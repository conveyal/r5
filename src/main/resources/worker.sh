#!/bin/bash
# Downloads and runs an analyst worker.
# This shell script undergoes variable substitution via the Java MessageFormat class before being passed to newly
# started worker machines via the AWS EC2 user data. MessageFormat will replace special tokens (consisting of numbers
# inside curly braces) with configuration information specific to the worker being started. These are:
# 0: the URL to grab the worker JAR from
# 1: the AWS log group to use
# 2: the worker configuration to use
# If you are reading this comment inside the EC2 user data field, this variable substitution has already happened.
# The string instance_id in curly brackets is substituted by EC2 at startup, not by our Java code. It and any shell
# variable references that contain brackets are single-quoted to tell Java's MessageFormat not to substitute them.

# prep the system: install log agent, java
yum -y install awslogs java-1.8.0-openjdk

# first things first: set up logging
LOGFILE=/var/log/analyst-worker.log

echo Starting analyst worker at `date` > $LOGFILE

# make it so that the worker can write to the logfile
chown ec2-user:ec2-user $LOGFILE
chmod 664 $LOGFILE # Log agent needs to read log file

# using a shell "herefile" or "heredoc", pipe the data between <<EOF and EOF into the cat process which then writes
# it to the appropriate location on the file system. Leave EOF unquoted so that variables are substituted.
cat > /etc/awslogs/awslogs.conf <<EOF
[general]
state_file = /var/lib/awslogs/agent-state

[logstream1]
file = $LOGFILE
log_group_name = {1}
log_stream_name = '{instance_id}'
datetime_format = %Y-%m-%dT%H:%M:%S%z
time_zone = UTC
EOF

REGION=`curl http://169.254.169.254/latest/meta-data/placement/availability-zone | egrep --only-matching '^[a-z-]+-[0-9]'`
cat > /etc/awslogs/awscli.conf <<EOF
[plugins]
cwlogs = cwlogs
[default]
region = $REGION
EOF

service awslogs start

cat > ~ec2-user/worker.conf <<EOF
{2}
EOF

# dump config and awslogs log to stdout so it ends up in the EC2 console
sleep 30
echo AWS Logs Config:
cat /etc/awslogs/awslogs.conf

echo AWS Logs CLI Config:
cat /etc/aws

echo AWS Log agent logs:
cat /var/log/awslogs.log

# Download the worker
sudo -u ec2-user wget -O ~ec2-user/r5.jar {0} >> $LOGFILE 2>&1

# Figure out how much memory to give the worker in kilobytes
TOTAL_MEM=`grep MemTotal /proc/meminfo | sed 's/[^0-9]//g'`
# 2097152 kb is 2GB, leave that much for the OS
MEM=`echo $TOTAL_MEM - 2097152 | bc`

# Start the worker
# run in ec2-user's home directory, in the subshell
{
    cd ~ec2-user
    sudo -u ec2-user java8 -Xmx$'{MEM}'k -jar r5.jar worker worker.conf >> $LOGFILE 2>&1

    # If the worker exits or doesn't start, wait a few minutes so that the CloudWatch log agent grabs
    # the logs
    sleep 120
    halt -p
} &
