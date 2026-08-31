package com.billrecord.ledger.data.local

import androidx.room.TypeConverter
import com.billrecord.shared.AccountType
import com.billrecord.shared.BookRole
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperationType
import com.billrecord.shared.TransactionType

class Converters {
    @TypeConverter fun transactionType(value: TransactionType) = value.name
    @TypeConverter fun transactionType(value: String) = TransactionType.valueOf(value)
    @TypeConverter fun accountType(value: AccountType) = value.name
    @TypeConverter fun accountType(value: String) = AccountType.valueOf(value)
    @TypeConverter fun bookRole(value: BookRole) = value.name
    @TypeConverter fun bookRole(value: String) = BookRole.valueOf(value)
    @TypeConverter fun reimbursement(value: ReimbursementStatus) = value.name
    @TypeConverter fun reimbursement(value: String) = ReimbursementStatus.valueOf(value)
    @TypeConverter fun syncEntity(value: SyncEntityType) = value.name
    @TypeConverter fun syncEntity(value: String) = SyncEntityType.valueOf(value)
    @TypeConverter fun syncOperation(value: SyncOperationType) = value.name
    @TypeConverter fun syncOperation(value: String) = SyncOperationType.valueOf(value)
}

