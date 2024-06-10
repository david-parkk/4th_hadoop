//package src;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.*;

import java.io.*;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;


public class DSC {

    public static boolean isWeekday(LocalDateTime dateTime) {
        DayOfWeek dayOfWeek = dateTime.getDayOfWeek();
        return !(dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY);
    }

    public static void inputToList(Path path, long diff, FileSystem fs, ArrayList<String> write_list, boolean before)
            throws IOException
    {
        if (fs.exists(path)) {
            BufferedReader read_csv_br = new BufferedReader(new InputStreamReader(fs.open(path)));
            String read_csv_line = read_csv_br.readLine();
            if (before == true)
            {
                for (int i = 0; i < diff; i++) {
                    read_csv_line = read_csv_br.readLine();
                    if (read_csv_line == null)
                        break ;
                    if (i <= diff - 30)
                    {
                        //TODO : add list
                        write_list.add(read_csv_line);
                    }
                }
            }
            else
            {
                for (int i = 0; i < diff + 30; i++) {
                    read_csv_line = read_csv_br.readLine();
                    if (read_csv_line == null)
                        break ;
                    if (i <= diff + 30)
                    {
                        write_list.add(read_csv_line);
                    }
                }
            }
        }
    }


    /**
     * Preprocessing
     * 실제로 hadoop상에서 제공하는 맵리듀스 라이브러리를 사용하지 않고 처리를 진행함.
     * 1. 먼저 다트의 파일을 읽고, 호재인지 악재인지 저장 + 기업 코드를 저장하게 됨.
     * 2. 기업 코드를 바탕으로 폴더에 접근해 처리를 진행함.
     * 2-a.만약, 앞 뒤 60분을 한 시간이 9시 ~ 15시 20분 시간을 초과하게 된다면, 전날 혹은 다음 날의 데이터를 바탕으로 처리함.
     * 3. 각각의 변동률, 수익률을 바로 전 분과 비교해 처리한다.
     * 4. 다트의 호재 악재를 저장하고, (기업코드_0).csv 형식으로 저장한다.
     * 4-a. 만약 0이 존재한다면 _1, _2...등등으로 증가시키면서 저장한다.
     */
    public static void preprocess(String inputFolder, String dartFolder, String outputFolder)
            throws IOException {

        Configuration conf = new Configuration();
        //hadoop filesystem에 접근하는 팩토리 메서드
        FileSystem fs = FileSystem.get(conf);

        //폴더를 읽어 처리하는 파일
        Path inFolder = new Path(inputFolder);
        Path daFolder = new Path(dartFolder);
        Path outFolder = new Path(outputFolder);

        LocalDateTime startTime = LocalDateTime.of(2023, 1,1,9,0);

        FSDataOutputStream outputStream = fs.create(outFolder);
        if (fs.exists(daFolder)) {
            FileStatus[] fileStatus = fs.listStatus(daFolder);
            for (FileStatus status : fileStatus) {
                //daFolder에 존재하는 파일을 읽고 처리하게 됨.
                Path dartFile = status.getPath();
                if (!status.isDirectory()) {
                    FSDataInputStream inputStream = null;
                    BufferedReader br = null;
                    try {
                        inputStream = fs.open(dartFile);
                        br = new BufferedReader(new InputStreamReader(inputStream));
                        //첫번째의 경우는 스킵.
                        String line = br.readLine();
                        while ((line = br.readLine()) != null) {
//                            System.out.println(line);
                            //combined_output.csv v파일 구조
                            /**
                             * 주식코드
                             * corp_code, corp_name, stock_code, report_num, rcept_no, recpt_dt, time, 호재성
                             * 실재 필요한 것은 stock_code, rcept_dt, time, 호재성(TRUE, FALSE);
                             * 0부터 시작하면 2, 5,6,7
                             */
                            String stock_code = line.split(",")[2];
                            String rcept_dt = line.split(",")[5];
                            String time = line.split(",")[6];
                            String hoze = line.split(",")[7];
                            if (stock_code.length() == 0 || rcept_dt.length() == 0 || time.length() == 0 || hoze.length() == 0) {
                                continue ;
                            }
                            else
                            {
                                ArrayList<String> write_list = new ArrayList<>();
                                write_list.add(hoze);
                                //TODO : find file
                                /**
                                 * 날짜, 시간, 파일 이름순
                                 */
                                /**
                                 * 분봉 csv파일 형식
                                 * prdy_vrss,prdy_vrss_sign,prdy_ctrt,stck_prdy_clpr,acml_vol,acml_tr_pbmn,hts_kor_isnm,stck_prpr,stck_bsop_date,stck_cntg_hour,stck_prpr,stck_oprc,stck_hgpr,stck_lwpr,cntg_vol,acml_tr_pbmn
                                 * (날짜(20230412)8, (시간 : 090000)9, (현재가)10, (1분간 거래량)14
                                 */
                                Integer hour = Integer.parseInt( time.split(":")[0]);
                                Integer minute = Integer.parseInt(time.split(":")[1]);
                                Integer year = Integer.parseInt(rcept_dt.substring(0, 4));
                                Integer month = Integer.parseInt(rcept_dt.substring(4, 6));
                                Integer day = Integer.parseInt(rcept_dt.substring(6, 8));
                                DateTimeFormatter folder_formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd");
                                LocalDateTime now = LocalDateTime.of(year, month, day, hour, minute);
                                LocalDateTime prev_now = now;
                                LocalDateTime future_now = now;
                                /**
                                 * hour가 9시 이전 혹은 9시 반 이전일 경우 / 15시 이후 혹은 14시 50분 초과일 경우
                                 */
                                while (isWeekday(prev_now))
                                {
                                    prev_now = now.minusDays(1);
                                }
                                while (isWeekday(future_now))
                                {
                                    future_now = future_now.plusDays(1);
                                }
                                if (hour <= 9 || (hour == 9 && minute < 30) || hour >= 3 || (hour == 2 && minute < 50))
                                {
                                    prev_now = prev_now.withHour(15).withMinute(20).withSecond(0).withNano(0);
                                    future_now = future_now.withHour(9).withMinute(0).withSecond(0).withNano(0);
                                }
                                String prev_str = inputFolder + prev_now.format(folder_formatter);
                                String future_str = inputFolder + future_now.format(folder_formatter);
                                String kospi = "kospi";
                                String kosdaq = "kosdaq";

                                //실제 파일이 존재하는 지 확인하는 코드
                                Path prev_kospi_path = new Path(prev_str + '/' + kospi + '/' + stock_code);
                                Path prev_kosdaq_path = new Path(prev_str + '/' + kospi + '/' + stock_code);
                                Path future_kospi_path = new Path(future_str + '/' + kosdaq + '/' + stock_code);
                                Path future_kosdaq_path = new Path(future_str + '/' + kospi + '/' + stock_code);
                                long diff = Duration.between(startTime, prev_now).toMinutes();
                                inputToList(prev_kospi_path, diff, fs, write_list, true);
                                inputToList(prev_kosdaq_path, diff, fs, write_list, true);
                                inputToList(future_kospi_path, diff, fs, write_list, false);
                                inputToList(future_kosdaq_path, diff, fs, write_list, false);

                                //TODO : write output file
                                Path output_file;
                                for (int i = 0; ; i++)
                                {
                                    output_file = new Path(outputFolder + '/' + stock_code + "_" + i);
                                    if (fs.exists(output_file) == false) {
                                        break;
                                    }
                                }
                                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fs.create(output_file)));
                                for (String l : write_list) {
                                    writer.write(l);
                                    writer.newLine();
                                }
                                writer.close();
                                System.out.println("파일 작성 완료 : " + output_file);
                            }
                        }
                    } finally {
                        IOUtils.closeStream(br);
                        IOUtils.closeStream(inputStream);
                    }
                }
            }
        }
        outputStream.close();
        fs.close();
    }

    /**
     * Mapper 상속 후 제너럴 클래스 타입 결정
     * 파일 구조 :
     * (호재 - 1, 악재 - 0)
     * (csv파일 형식으로 되어 있는 60줄짜리 파일 형식)
     * 엔터가 두번 나오기 전까지 각 줄은 dart에서 가져온 전처리된 자료
     * 이후 시간이작성되어 있는 csv파일 형식을 따름
     * MyMapper : 하나의 엔터당 하나의 Context가 나오게 됨.
     * 전체 파일을 스캔하면서 context에 하나씩 작성.
     * key : company code
     * value : 0: 30분 이내의 데이터를 시간정보와 함께 직렬화, 구분은 :으로
     *  1: 60분 이내의 데이터를 시간 정보와 함께 직렬화.
     * 1.
     */
        public static class MyMapper
                extends Mapper<Object, Text, Text, Text> {
            private Text word = new Text();

            public void map(Object key, Text value, Context context) throws IOException, InterruptedException {

            }
        }

    /**
     * Reducer
     * Context : 호재, 악재가 key값, value의 경우 공시가 발행된 앞 뒤 60분간격의  csv파일이 한 value마다 작성되어 있음.
     * 각각의 줄을 모으고, 60 ~ 30 / 30 ~ 0 ~ 30 / 30 ~ 60까지의 데이터로 전처리함.
     * 이 줄들을 분석한 다음, 변동률, 수익률을 가지고 나이브한 형식으로 value에 작성함.
     * output : key : dart에서 분석한 호재, 악재, value : 주식 가격의 변동률, 수익률, 얼마나 작용한지
     */
        public static class MyReducer
                extends Reducer<Text, Text, Text, Text> {
            protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {

            }

            /**
             * Afterprocessing
             * 1. 구현하지 않는다.
             */
        }

    public static void main(String[] args) throws Exception {
        String inputFolder = args[0];
        String dartFolder = args[1];
        String outputFolder = args[2];

        Configuration conf = new Configuration();

//            Job job = Job.getInstance(conf, "preprocess");
//
//            job.setJarByClass(preprocess.class);

        preprocess(inputFolder, dartFolder, outputFolder);
    }

}