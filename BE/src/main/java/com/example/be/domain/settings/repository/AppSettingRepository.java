package com.example.be.domain.settings.repository;

import com.example.be.domain.settings.entity.AppSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT setting FROM AppSetting setting WHERE setting.id = :id")
    Optional<AppSetting> findByIdForUpdate(@Param("id") Long id);
}
