// $Id$
// $Log: not supported by cvs2svn $
// Revision 1.7  2006/03/24 00:29:03  timur
// regenerated stubs with array wrappers for v2_1
//
// Revision 1.6  2006/03/22 01:03:11  timur
// use abort files instead or release in case of interrupts or failures, better CTRL-C handling
//
// Revision 1.5  2006/03/14 18:10:04  timur
// moving toward the axis 1_3
//
// Revision 1.4  2006/02/27 22:54:25  timur
//  do not use Keep Space parameter in srmReleaseFiles, reduce default wait time
//
// Revision 1.3  2005/12/14 01:58:44  timur
// srmPrepareToGet client is ready
//
// Revision 1.2  2005/12/09 00:24:51  timur
// srmPrepareToGet works
//
// Revision 1.1  2005/12/07 02:05:22  timur
// working towards srm v2 get client
//
// Revision 1.8  2005/04/27 16:39:59  timur
// more work on report added gridftpcopy and adler32 binaries
//
// Revision 1.7  2005/04/26 02:06:08  timur
// added the ability to create a report file
//
// Revision 1.6  2005/03/11 21:18:36  timur
// making srm compatible with cern tools again
//
// Revision 1.5  2005/01/25 23:20:20  timur
// srmclient now uses srm libraries
//
// Revision 1.4  2004/06/30 21:57:04  timur
//  added retries on each step, added the ability to use srmclient used by srm copy in the server, added srm-get-request-status
//

/*
COPYRIGHT STATUS:
  Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
  software are sponsored by the U.S. Department of Energy under Contract No.
  DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
  non-exclusive, royalty-free license to publish or reproduce these documents
  and software for U.S. Government purposes.  All documents and software
  available from this server are protected under the U.S. and Foreign
  Copyright Laws, and FNAL reserves all rights.
 
 
 Distribution of the software available from this server is free of
 charge subject to the user following the terms of the Fermitools
 Software Legal Information.
 
 Redistribution and/or modification of the software shall be accompanied
 by the Fermitools Software Legal Information  (including the copyright
 notice).
 
 The user is asked to feed back problems, benefits, and/or suggestions
 about the software to the Fermilab Software Providers.
 
 
 Neither the name of Fermilab, the  URA, nor the names of the contributors
 may be used to endorse or promote products derived from this software
 without specific prior written permission.
 
 
 
  DISCLAIMER OF LIABILITY (BSD):
 
  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
  "AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
  LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
  FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
  OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
  FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
  CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
  OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
  BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.
 
 
  Liabilities of the Government:
 
  This software is provided by URA, independent from its Prime Contract
  with the U.S. Department of Energy. URA is acting independently from
  the Government and in its own private capacity and is not acting on
  behalf of the U.S. Government, nor as its contractor nor its agent.
  Correspondingly, it is understood and agreed that the U.S. Government
  has no connection to this software and in no manner whatsoever shall
  be liable for nor assume any responsibility or obligation for any claim,
  cost, or damages arising out of or resulting from the use of the software
  available from this server.
 
 
  Export Control:
 
  All documents and software available from this server are subject to U.S.
  export control laws.  Anyone downloading information from this server is
  obligated to secure any necessary Government licenses before exporting
  documents or software obtained from this server.
 */


/*
 * CopyJob.java
 *
 * Created on January 28, 2003, 1:37 PM
 */

package gov.fnal.srm.util;

import org.globus.util.GlobusURL;
import org.dcache.srm.v2_2.*;
import org.dcache.srm.Logger;
/**
 *
 * @author  timur
 */

public class SRMV2CopyJob implements CopyJob {
    private GlobusURL from;
    private GlobusURL to;
    private ISRM srm;
    private boolean isDone;
    private Logger logger;
    
    // added these to support generation of the report
    private GlobusURL surl;
    private boolean isSrmPrepareToGet;
    private SRMClient client;
    private String requestToken;

    
    public SRMV2CopyJob(GlobusURL from, GlobusURL to, ISRM srm, String requestToken, Logger logger, GlobusURL surl, boolean isSrmPrepareToGet, SRMClient client) {
        if(from == null || to == null) {
            throw new IllegalArgumentException("both source and destination"+
            "must be non-null");
        }
        this.from = from;
        this.to = to;
        this.srm = srm;
        this.requestToken = requestToken;
        this.logger = logger;
        this.surl = surl;
        this.isSrmPrepareToGet = isSrmPrepareToGet;
        this.client = client;
    }
    
    public SRMV2CopyJob(GlobusURL from, GlobusURL to, Logger logger, GlobusURL surl, boolean isSrmPrepareToGet, SRMClient client) {
        this(from,to,(ISRM)null,null,logger,surl,isSrmPrepareToGet,client);
        
    }
    public GlobusURL getSource() {
        return from;
    }
    public GlobusURL getDestination() {
        return to;
    }
    
    public boolean equals(Object o) {
        if (o == null || !(o instanceof SRMV2CopyJob)) {
            return false;
        }
        
        SRMV2CopyJob copy_job = (SRMV2CopyJob) o;
        
        return to.equals(copy_job.to) && from.equals(copy_job.from);
        
    }
    
    public String toString() {
        return "CopyJob, source = "+from.getURL()+
        " destination = "+to.getURL();
    }
    
    public void done(boolean success, String error) {
        synchronized(this) {
            if(isDone) {
                return;
            }
        }
        if(success) {
            if(isSrmPrepareToGet) {
                client.setReportSucceeded(surl,null);
            }
            else {
                client.setReportSucceeded(null,surl);
            }
                
        }
        else
        {   
            error = "received TURL but failed to copy: "+error;
            if(isSrmPrepareToGet) {
                client.setReportFailed(surl,null,error);
            } else {
                client.setReportFailed(null,surl,error);
            }  
        
        }
        
        if(srm != null) {
            try {
                org.apache.axis.types.URI surlArray[] = new org.apache.axis.types.URI[1];
                surlArray[0] = 
                        new org.apache.axis.types.URI(surl.getURL());
                if(success) {
                    if(isSrmPrepareToGet) {
                        SrmReleaseFilesRequest srmReleaseFilesRequest = new SrmReleaseFilesRequest();
                        srmReleaseFilesRequest.setRequestToken(requestToken);
                        srmReleaseFilesRequest.setArrayOfSURLs(
                            new ArrayOfAnyURI(surlArray));
                        //srmReleaseFilesRequest.setKeepSpace(Boolean.FALSE);
                        SrmReleaseFilesResponse srmReleaseFilesResponse = 
                            srm.srmReleaseFiles(srmReleaseFilesRequest);
                        TReturnStatus returnStatus = srmReleaseFilesResponse.getReturnStatus();
                        if(returnStatus == null) {
                            logger.elog("srmReleaseFilesResponse return status is null");
                            return;
                        }
                        logger.log("srmReleaseFilesResponse status code="+returnStatus.getStatusCode());
                    } else {
                        SrmPutDoneRequest srmPutDoneRequest = new SrmPutDoneRequest();
                        srmPutDoneRequest.setRequestToken(requestToken);
                        srmPutDoneRequest.setArrayOfSURLs(
                            new ArrayOfAnyURI(surlArray));
                        SrmPutDoneResponse srmPutDoneResponse = 
                            srm.srmPutDone(srmPutDoneRequest);
                        TReturnStatus returnStatus = srmPutDoneResponse.getReturnStatus();
                        if(returnStatus == null) {
                            logger.elog("srmPutDone return status is null");
                            return;
                        }
                        logger.log("srmPutDone status code="+returnStatus.getStatusCode());

                    }
                }
                else {
                    SrmAbortFilesRequest srmAbortFilesRequest = new SrmAbortFilesRequest();
                    srmAbortFilesRequest.setRequestToken(requestToken);
                    srmAbortFilesRequest.setArrayOfSURLs(
                        new ArrayOfAnyURI(surlArray));
                    SrmAbortFilesResponse srmAbortFilesResponse = srm.srmAbortFiles(srmAbortFilesRequest);
                    if(srmAbortFilesResponse == null) {
                        logger.elog(" srmAbortFilesResponse is null");
                    } else {
                        TReturnStatus returnStatus = srmAbortFilesResponse.getReturnStatus();
                        if(returnStatus == null) {
                                logger.elog("srmAbortFiles return status is null");
                                return;
                        }
                        logger.log("srmAbortFiles status code="+returnStatus.getStatusCode());
                    }

                }

            } catch(Exception e) {
                logger.elog(e.toString());
            }
            
        }
        synchronized(this) {
            isDone = true;
        }
    }
}
