/**
 * Copyright 2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package actors;

import akka.actor.UntypedActor;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Properties;
import metadata.etl.models.EtlJobStatus;
import models.daos.EtlJobDao;
import models.daos.EtlJobPropertyDao;
import msgs.EtlJobMessage;
import play.Logger;
import shared.Global;


/**
 * Created by zechen on 9/4/15.
 */
public class EtlJobActor extends UntypedActor {

  private Process process;
  @Override
  public void onReceive(Object message)
    throws Exception {
    Properties props = null;
    if (message instanceof EtlJobMessage) {
      EtlJobMessage msg = (EtlJobMessage) message;
      try {
        props = EtlJobPropertyDao.getJobProperties(msg.getEtlJobName(), msg.getRefId());
        Properties whProps = EtlJobPropertyDao.getWherehowsProperties();
        props.putAll(whProps);
        EtlJobDao.startRun(msg.getWhEtlExecId(), "Job started!");

        // start a new process here
        String cmd = ConfigUtil.generateCMD(msg.getWhEtlExecId(), msg.getCmdParam());
        Logger.debug("run command : " + cmd);
        ConfigUtil
            .generateProperties(msg.getEtlJobName(), msg.getRefId(), msg.getWhEtlExecId(), props);
        process = Runtime.getRuntime().exec(cmd);

        InputStream stdout = process.getInputStream();
        InputStreamReader isr = new InputStreamReader(stdout);
        BufferedReader br = new BufferedReader(isr);
        String line = null;
        while ( (line = br.readLine()) != null) {
          Logger.info(line);
        }

        // wait until this process finished.
        int execResult = process.waitFor();

        // if the process failed, log the error and throw exception
        if (execResult > 0) {
          br = new BufferedReader(new InputStreamReader(process.getErrorStream()));
          String errString = "Error Details:\n";
          while((line = br.readLine()) != null)
            errString = errString.concat(line).concat("\n");
          Logger.error("*** Process + " + getPid(process) + " failed, status: " + execResult);
          Logger.error(errString);
          throw new Exception("Process + " + getPid(process) + " failed");
        }

        EtlJobDao.endRun(msg.getWhEtlExecId(), EtlJobStatus.SUCCEEDED, "Job succeed!");
        Logger.info("ETL job {} finished", msg.toDebugString());

        if (msg.getEtlJobName().affectDataset()) {
          ActorRegistry.treeBuilderActor.tell("dataset", getSelf());
        }

        if (msg.getEtlJobName().affectFlow()) {
          ActorRegistry.treeBuilderActor.tell("flow", getSelf());
        }
      } catch (Throwable e) { // catch all throwable at the highest level.
        e.printStackTrace();
        Logger.error("ETL job {} got a problem", msg.toDebugString());
        if (process.isAlive()) {
          process.destroy();
        }
        EtlJobDao.endRun(msg.getWhEtlExecId(), EtlJobStatus.ERROR, e.getMessage());
      } finally {
        Global.removeRunningJob(((EtlJobMessage) message).getWhEtlJobId());
        if (!Logger.isDebugEnabled()) // if debug enable, won't delete the config files.
          ConfigUtil.deletePropertiesFile(props, msg.getWhEtlExecId());
      }
    }
  }

  /**
   * Reflection to get the pid
   * @param process {@code Process}
   * @return pid, -1 if not found
   */
  private static int getPid(Process process) {
    try {
      Class<?> cProcessImpl = process.getClass();
      Field fPid = cProcessImpl.getDeclaredField("pid");
      if (!fPid.isAccessible()) {
        fPid.setAccessible(true);
      }
      return fPid.getInt(process);
    } catch (Exception e) {
      return -1;
    }
  }


}
