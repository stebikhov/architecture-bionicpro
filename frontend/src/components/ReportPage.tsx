import React, { useState, useEffect } from 'react';

interface UserProfileData {
  id?: string;
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  clientName?: string;
  provider?: string;
  consentGiven?: boolean;
}

interface ReportData {
  username?: string;
  clientName?: string;
  prostheticId?: string;
  prostheticModel?: string;
  requestedPeriodStart?: string;
  requestedPeriodEnd?: string;
  processedBoundaryDate?: string;
  containsUnprocessedPeriod?: boolean;
  notice?: string;
  daysCovered?: number;
  totalActiveSeconds?: number;
  totalGesturesPerformed?: number;
  avgMuscleSignalAmplitude?: number;
  avgBatteryLevel?: number;
  avgResponseLatencyMs?: number;
  totalWarnings?: number;
  cdnUrl?: string;
  s3Key?: string;
  cacheStatus?: string;
}

const ReportPage: React.FC = () => {
  const [authenticated, setAuthenticated] = useState<boolean>(false);
  const [checkingAuth, setCheckingAuth] = useState<boolean>(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [username, setUsername] = useState<string>('');
  const [provider, setProvider] = useState<string>('keycloak');
  const [consentRequired, setConsentRequired] = useState<boolean>(false);
  const [profile, setProfile] = useState<UserProfileData | null>(null);
  const [savingConsent, setSavingConsent] = useState<boolean>(false);

  // Период отчёта
  const [startDate, setStartDate] = useState<string>('2026-10-01');
  const [endDate, setEndDate] = useState<string>('2026-10-16');
  const [reportResult, setReportResult] = useState<ReportData | null>(null);

  const authUrl = process.env.REACT_APP_AUTH_URL || 'http://localhost:8081';
  const apiUrl = process.env.REACT_APP_API_URL || 'http://localhost:8081/api';

  useEffect(() => {
    checkAuthStatus();
  }, []);

  const checkAuthStatus = async () => {
    try {
      setCheckingAuth(true);
      const response = await fetch(`${authUrl}/auth/status`, {
        method: 'GET',
        credentials: 'include',
        headers: {
          'Accept': 'application/json'
        }
      });

      if (response.ok) {
        const data = await response.json();
        setAuthenticated(data.authenticated === true);
        setUsername(data.username || '');
        setProvider(data.provider || 'keycloak');
        setConsentRequired(data.consentRequired === true);
        setProfile(data.profile || null);
      } else {
        setAuthenticated(false);
      }
    } catch (err) {
      setAuthenticated(false);
    } finally {
      setCheckingAuth(false);
    }
  };

  const handleLogin = (idpHint?: string) => {
    const targetUrl = idpHint ? `${authUrl}/auth/login?idp=${idpHint}` : `${authUrl}/auth/login`;
    window.location.href = targetUrl;
  };

  const handleConsent = async (consent: boolean) => {
    try {
      setSavingConsent(true);
      setError(null);

      const response = await fetch(`${authUrl}/auth/consent`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        body: JSON.stringify({ consent })
      });

      if (response.ok) {
        if (consent) {
          setConsentRequired(false);
          setSuccessMessage('Согласие предоставлено. Данные профиля Яндекс сохранены в БД.');
        } else {
          setError('В согласии отказано. Для доступа к защищенным отчетам требуется согласие.');
        }
      } else {
        setError('Не удалось сохранить решение');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка при сохранении согласия');
    } finally {
      setSavingConsent(false);
    }
  };

  const handleLogout = async () => {
    try {
      const response = await fetch(`${authUrl}/auth/logout`, {
        method: 'POST',
        credentials: 'include'
      });
      setAuthenticated(false);
      setSuccessMessage(null);
      setProfile(null);
      setConsentRequired(false);
      setReportResult(null);

      if (response.ok) {
        const data = await response.json();
        if (data.logoutUrl) {
          // Redirect through Keycloak logout to completely clear browser SSO cookies
          window.location.href = data.logoutUrl;
        }
      }
    } catch (err) {
      console.error('Logout error', err);
      setAuthenticated(false);
    }
  };

  const downloadReport = async () => {
    try {
      setLoading(true);
      setError(null);
      setSuccessMessage(null);

      const queryParams = new URLSearchParams({
        startDate: startDate,
        endDate: endDate
      });

      const response = await fetch(`${apiUrl}/reports?${queryParams.toString()}`, {
        method: 'GET',
        credentials: 'include',
        headers: {
          'Accept': 'application/json'
        }
      });

      if (response.status === 401) {
        setAuthenticated(false);
        setError('Сессия истекла или пользователь не аутентифицирован. Пожалуйста, войдите снова.');
        return;
      }

      if (response.status === 403) {
        const body = await response.json();
        if (body.consentRequired) {
          setConsentRequired(true);
          setError('Необходимо предоставить согласие на использование данных профиля Яндекс перед получением отчёта.');
          return;
        }
        setError(body.error || 'Доступ запрещен: можно запрашивать отчёт только по собственному пользователю.');
        return;
      }

      if (!response.ok) {
        throw new Error(`Сервис отчетов вернул ошибку: ${response.status} ${response.statusText}`);
      }

      const reportJson: ReportData = await response.json();
      setReportResult(reportJson);

      // Скачивание файла отчета
      const blob = new Blob([JSON.stringify(reportJson, null, 2)], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `bionicpro-report-${reportJson.prostheticId || 'prosthetic'}-${startDate}-${endDate}.json`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      a.remove();

      setSuccessMessage('Отчёт успешно сформирован из ClickHouse OLAP и загружен!');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Произошла ошибка при получении отчёта');
    } finally {
      setLoading(false);
    }
  };

  if (checkingAuth) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100">
        <div className="text-lg font-medium text-gray-700">Проверка аутентификации...</div>
      </div>
    );
  }

  if (!authenticated) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
        <div className="p-8 bg-white rounded-lg shadow-md max-w-sm w-full text-center">
          <h1 className="text-2xl font-bold mb-4 text-gray-800">BionicPRO</h1>
          <p className="text-sm text-gray-600 mb-6">Безопасный вход через Keycloak SSO (PKCE + BFF сессии)</p>
          
          <button
            onClick={() => handleLogin()}
            className="w-full mb-3 px-4 py-2 bg-blue-600 text-white font-medium rounded hover:bg-blue-700 transition"
          >
            Войти через Keycloak SSO
          </button>

          <button
            onClick={() => handleLogin('yandex')}
            className="w-full px-4 py-2 bg-red-600 text-white font-medium rounded hover:bg-red-700 transition flex items-center justify-center gap-2"
          >
            <span>Войти с Яндекс ID</span>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100 p-4">
      <div className="p-8 bg-white rounded-lg shadow-md max-w-2xl w-full">
        <div className="flex justify-between items-center mb-4">
          <h1 className="text-2xl font-bold text-gray-800">Отчёты о работе протеза</h1>
          <button
            onClick={handleLogout}
            className="text-sm text-red-600 hover:text-red-800 font-medium"
          >
            Выйти
          </button>
        </div>

        <div className="mb-4 p-3 bg-gray-50 rounded text-xs text-gray-600 flex justify-between">
          <div>Пользователь: <strong className="text-gray-900">{username}</strong> ({profile?.clientName || 'Пилот протеза'})</div>
          <div>Провайдер: <strong className="text-gray-900">{provider}</strong></div>
        </div>

        {/* Согласие для Яндекс ID */}
        {consentRequired && (
          <div className="mb-6 p-4 border border-yellow-300 bg-yellow-50 rounded-lg">
            <h3 className="font-semibold text-yellow-800 text-sm mb-2">Согласие на использование данных профиля Яндекс</h3>
            <p className="text-xs text-yellow-700 mb-3">
              Для формирования отчёта сервису BionicPRO требуется согласие на сохранение профиля в БД.
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => handleConsent(true)}
                disabled={savingConsent}
                className="flex-1 px-3 py-1.5 bg-yellow-600 text-white text-xs font-semibold rounded hover:bg-yellow-700 transition"
              >
                {savingConsent ? 'Сохранение...' : 'Разрешить'}
              </button>
              <button
                onClick={() => handleConsent(false)}
                disabled={savingConsent}
                className="px-3 py-1.5 bg-gray-300 text-gray-800 text-xs font-medium rounded hover:bg-gray-400 transition"
              >
                Отклонить
              </button>
            </div>
          </div>
        )}

        {/* Выбор периода отчёта */}
        <div className="mb-6 p-4 bg-gray-50 rounded-lg border border-gray-200">
          <h2 className="text-sm font-semibold text-gray-700 mb-3">Параметры периода отчёта</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs text-gray-600 mb-1">Дата начала</label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="w-full px-3 py-1.5 border border-gray-300 rounded text-sm"
              />
            </div>
            <div>
              <label className="block text-xs text-gray-600 mb-1">Дата окончания</label>
              <input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="w-full px-3 py-1.5 border border-gray-300 rounded text-sm"
              />
            </div>
          </div>
        </div>

        <button
          onClick={downloadReport}
          disabled={loading || consentRequired}
          className={`w-full px-4 py-2 bg-blue-600 text-white font-medium rounded hover:bg-blue-700 transition ${
            loading || consentRequired ? 'opacity-50 cursor-not-allowed' : ''
          }`}
        >
          {loading ? 'Формирование отчёта из OLAP...' : 'Получить и скачать отчёт'}
        </button>

        {reportResult?.notice && (
          <div className="mt-4 p-3 bg-yellow-50 border border-yellow-200 text-yellow-800 rounded text-xs">
            {reportResult.notice}
          </div>
        )}

        {successMessage && (
          <div className="mt-4 p-3 bg-green-100 text-green-700 rounded text-sm">
            {successMessage}
          </div>
        )}

        {error && (
          <div className="mt-4 p-3 bg-red-100 text-red-700 rounded text-sm">
            {error}
          </div>
        )}

        {/* Просмотр сформированного отчета */}
        {reportResult && (
          <div className="mt-6 p-4 border border-gray-200 rounded-lg">
            <div className="flex justify-between items-center mb-3">
              <h3 className="font-semibold text-gray-800 text-sm">Сводка отчёта ({reportResult.prostheticModel})</h3>
              {reportResult.cacheStatus && (
                <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-medium ${
                  reportResult.cacheStatus.includes('HIT') 
                    ? 'bg-green-100 text-green-800 border border-green-300' 
                    : 'bg-blue-100 text-blue-800 border border-blue-300'
                }`}>
                  {reportResult.cacheStatus.includes('HIT') ? '⚡ S3 / CDN CACHE HIT' : '🔄 OLAP GENERATED & S3 CACHED'}
                </span>
              )}
            </div>

            {reportResult.cdnUrl && (
              <div className="mb-3 p-2 bg-blue-50 border border-blue-100 rounded text-xs flex justify-between items-center">
                <span className="text-blue-900 truncate max-w-md">CDN URL: <a href={reportResult.cdnUrl} target="_blank" rel="noreferrer" className="underline font-mono">{reportResult.cdnUrl}</a></span>
                <a
                  href={reportResult.cdnUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="px-2 py-1 bg-blue-600 text-white rounded text-[11px] font-medium hover:bg-blue-700"
                >
                  Открыть через CDN
                </a>
              </div>
            )}

            <div className="grid grid-cols-2 gap-3 text-xs text-gray-700">
              <div className="p-2 bg-gray-50 rounded">Протез ID: <strong>{reportResult.prostheticId}</strong></div>
              <div className="p-2 bg-gray-50 rounded">Дней в выборке: <strong>{reportResult.daysCovered}</strong></div>
              <div className="p-2 bg-gray-50 rounded">Всего жестов: <strong>{reportResult.totalGesturesPerformed}</strong></div>
              <div className="p-2 bg-gray-50 rounded">Активное время: <strong>{Math.round((reportResult.totalActiveSeconds || 0) / 3600)} ч</strong></div>
              <div className="p-2 bg-gray-50 rounded">Средняя задержка: <strong>{reportResult.avgResponseLatencyMs} мс</strong></div>
              <div className="p-2 bg-gray-50 rounded">Средний заряд батареи: <strong>{reportResult.avgBatteryLevel}%</strong></div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ReportPage;
