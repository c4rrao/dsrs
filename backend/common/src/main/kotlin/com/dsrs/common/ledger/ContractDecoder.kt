package com.dsrs.common.ledger

import com.daml.ledger.api.v1.EventOuterClass.CreatedEvent
import com.daml.ledger.api.v1.ValueOuterClass.Record
import com.daml.ledger.javaapi.data.DamlRecord
import com.daml.ledger.javaapi.data.codegen.ContractCompanion
import com.daml.ledger.javaapi.data.codegen.ValueDecoder
import java.util.Optional

object ContractDecoder {

    fun <T> decode(
        record: Record,
        decoder: ValueDecoder<T>
    ): T {
        val damlRecord = DamlRecord.fromProto(record)
        return decoder.decode(damlRecord)
    }

    fun <C : com.daml.ledger.javaapi.data.codegen.Contract<Cid, T>, Cid, T> decodeContract(
        event: CreatedEvent,
        companion: ContractCompanion.WithoutKey<C, Cid, T>
    ): C {
        val javaEvent = com.daml.ledger.javaapi.data.CreatedEvent.fromProto(event)
        return companion.fromCreatedEvent(javaEvent)
    }

    fun <T> decodeOrNull(
        record: Record,
        decoder: ValueDecoder<T>
    ): T? {
        return try {
            decode(record, decoder)
        } catch (e: Exception) {
            null
        }
    }

    fun <T : Any> decodeOptional(
        record: Record,
        decoder: ValueDecoder<T>
    ): Optional<T> {
        return try {
            Optional.of(decode(record, decoder))
        } catch (e: Exception) {
            Optional.empty()
        }
    }
}
