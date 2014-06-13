package org.dcache.webadmin.view.beans;

import java.io.Serializable;

/**
 *
 * @author jans
 */
public class ActiveTransfersBean implements Serializable {

    private static final long serialVersionUID = -3543858928593598739L;
    private String _cellName = "";
    private String _cellDomainName = "";
    private String _protocolFamily = "<unkown>";
    private String _protocolVersion = "<unknown>";
    private String _owner = "<unknown>";
    private String _process = "<unknown>";
    private long _serialId;
    private String _pnfsId = "";
    private String _pool = "";
    private String _replyHost = "";
    private String _status = "";
    private long _waitingSince;
    private String _state = "";
    private long _bytesTransferred;
    private long _transferTime;
    private long _lastTransferred;
    private long _jobId;

    public Key getKey()
    {
        return new Key(getCellDomainName(), getCellName(), getSerialId());
    }

    public String getWaitingSinceTime() {
        int sec = (int) ((System.currentTimeMillis() - _waitingSince) / 1000L);
        int min = sec / 60;
        sec = sec % 60;
        int hour = min / 60;
        min = min % 60;
        int day = hour / 24;
        hour = hour % 24;

        String sS = Integer.toString(sec);
        String mS = Integer.toString(min);
        String hS = Integer.toString(hour);

        StringBuilder sb = new StringBuilder();
        if (day > 0) {
            sb.append(day).append(" d ");
        }
        sb.append(hS.length() < 2 ? "0" : "").append(hS).append(":");
        sb.append(mS.length() < 2 ? "0" : "").append(mS).append(":");
        sb.append(sS.length() < 2 ? "0" : "").append(sS);

        return sb.toString();
    }

    /** Returns transfer rate in kB/s (decimal). */
    public long getTransferRate() {
        return (_transferTime > 0)
                ? _bytesTransferred / _transferTime
                : 0;
    }

    /** Returns data transferred so far in kB (decimal). */
    public long getTransferred() {
        return _bytesTransferred / 1000;
    }

    public long getBytesTransferred() {
        return _bytesTransferred;
    }

    public void setBytesTransferred(long bytesTransferred) {
        _bytesTransferred = bytesTransferred;
    }

    public String getCellDomainName() {
        return _cellDomainName;
    }

    public void setCellDomainName(String cellDomainName) {
        _cellDomainName = cellDomainName;
    }

    public String getCellName() {
        return _cellName;
    }

    public void setCellName(String cellName) {
        _cellName = cellName;
    }

    public long getJobId() {
        return _jobId;
    }

    public void setJobId(long jobId) {
        _jobId = jobId;
    }

    public long getLastTransferred() {
        return _lastTransferred;
    }

    public void setLastTransferred(long lastTransferred) {
        _lastTransferred = lastTransferred;
    }

    public String getOwner() {
        return _owner;
    }

    public void setOwner(String owner) {
        _owner = owner;
    }

    public String getPnfsId() {
        return _pnfsId;
    }

    public void setPnfsId(String pnfsId) {
        _pnfsId = pnfsId;
    }

    public String getPool() {
        return _pool;
    }

    public void setPool(String pool) {
        _pool = pool;
    }

    public String getProcess() {
        return _process;
    }

    public void setProcess(String process) {
        _process = process;
    }

    public long getSerialId() {
        return _serialId;
    }

    public String getSerialIdString() {
        return Long.valueOf(_serialId).toString();
    }

    public void setSerialId(long serialId) {
        _serialId = serialId;
    }

    public String getProtocolFamily() {
        return _protocolFamily;
    }

    public void setProtocolFamily(String protocolFamily) {
        _protocolFamily = protocolFamily;
    }

    public String getProtocolVersion() {
        return _protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        _protocolVersion = protocolVersion;
    }

    public String getProtocol() {
        return getProtocolFamily() + '-' + getProtocolVersion();
    }

    public String getReplyHost() {
        return _replyHost;
    }

    public void setReplyHost(String replyHost) {
        _replyHost = replyHost;
    }

    public String getState() {
        return _state;
    }

    public void setState(String state) {
        _state = state;
    }

    public String getStatus() {
        return _status;
    }

    public void setStatus(String status) {
        _status = status;
    }

    public long getTransferTime() {
        return _transferTime;
    }

    public void setTransferTime(long transferTime) {
        _transferTime = transferTime;
    }

    public long getWaitingSince() {
        return _waitingSince;
    }

    public void setWaitingSince(long waitingSince) {
        _waitingSince = waitingSince;
    }

    public static class Key
    {
        private final String domainName;
        private final String cellName;
        private final long serialId;

        public Key(String domainName, String cellName, long serialId)
        {

            this.domainName = domainName;
            this.cellName = cellName;
            this.serialId = serialId;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Key key = (Key) o;
            return (serialId == key.serialId) && cellName.equals(key.cellName) && domainName.equals(key.domainName);

        }

        @Override
        public int hashCode()
        {
            int result = domainName.hashCode();
            result = 31 * result + cellName.hashCode();
            result = 31 * result + (int) (serialId ^ (serialId >>> 32));
            return result;
        }
    }
}
