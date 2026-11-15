package net.prototux.aare.aap

/** Only timestamp plus an optional false bug_report. No unknown fields or report/data requests. */
internal object EarlyHandshakePing {
    fun timestamp(bytes:ByteArray):Long? {
        if(bytes.size !in 4..16 || bytes[0].toInt()!=0 || bytes[1].toInt()!=ControlMsg.PING_REQUEST || bytes[2].toInt()!=8)return null
        var value=0L
        var index=3
        var count=0
        while(index<bytes.size && count<9) {
            val b=bytes[index++].toInt() and 255
            value=value or ((b and 127).toLong() shl (count*7))
            count++
            if(b and 128==0) {
                if(count>1 && b==0)return null // Non-canonical varint.
                if(index==bytes.size)return value
                return if(index+2==bytes.size && bytes[index].toInt()==16 && bytes[index+1].toInt()==0)value else null
            }
        }
        return null
    }
}
