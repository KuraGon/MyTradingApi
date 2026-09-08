package com.saamp.trading.as400;

/** Identite physique durable et progression individuelle, independantes du claim parent. */
record As400Movement(long id, int legIndex, String legRole, SicouviMovement data,
        As400SyncState state, Integer siprov) {
    As400Movement progress(int provisional, boolean settled) {
        if (siprov != null && siprov != provisional)
            throw new As400SyncDataException("AS400_SIPROV_CHANGED");
        return new As400Movement(id,legIndex,legRole,data,
                settled ? As400SyncState.SETTLED :
                provisional > 0 ? As400SyncState.ACCEPTED : As400SyncState.SUBMITTED,
                provisional > 0 ? provisional : null);
    }
}
